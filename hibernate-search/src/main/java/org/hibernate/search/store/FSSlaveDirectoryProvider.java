/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2010, Red Hat, Inc. and/or its affiliates or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat, Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.search.store;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;

import org.hibernate.annotations.common.AssertionFailure;
import org.hibernate.search.Environment;
import org.hibernate.search.spi.BuildContext;
import org.hibernate.search.SearchException;
import org.hibernate.search.util.FileHelper;
import org.hibernate.search.util.LoggerFactory;

/**
 * File based directory provider that takes care of getting a version of the index
 * from a given source.
 * The base directory is represented by hibernate.search.<index>.indexBase
 * The index is created in <base directory>/<index name>
 * The source (aka copy) directory is built from <sourceBase>/<index name>
 * <p/>
 * A copy is triggered every refresh seconds
 *
 * @author Emmanuel Bernard
 * @author Sanne Grinovero
 */
public class FSSlaveDirectoryProvider implements DirectoryProvider<FSDirectory> {

	private static final Logger log = LoggerFactory.make();
	private final Timer timer = new Timer( true ); //daemon thread, the copy algorithm is robust

	private volatile int current; //used also as memory barrier of all other values, which are set once.

	//variables having visibility granted by a read of "current"
	private FSDirectory directory1;
	private FSDirectory directory2;
	private String indexName;
	private long copyChunkSize;

	//variables needed between initialize and start (used by same thread: no special care needed)
	private File sourceIndexDir;
	private File indexDir;
	private String directoryProviderName;
	private Properties properties;
	private TriggerTask task;

	public void initialize(String directoryProviderName, Properties properties, BuildContext context) {
		this.properties = properties;
		this.directoryProviderName = directoryProviderName;
		//source guessing
		sourceIndexDir = DirectoryProviderHelper.getSourceDirectory( directoryProviderName, properties, false );
		checkCurrentMarkerInSource();
		log.debug( "Source directory: {}", sourceIndexDir.getPath() );
		indexDir = DirectoryProviderHelper.getVerifiedIndexDir( directoryProviderName, properties, true );
		log.debug( "Index directory: {}", indexDir.getPath() );
		try {
			indexName = indexDir.getCanonicalPath();
		}
		catch ( IOException e ) {
			throw new SearchException( "Unable to initialize index: " + directoryProviderName, e );
		}
		copyChunkSize = DirectoryProviderHelper.getCopyBufferSize( directoryProviderName, properties );
		current = 0; //publish all state to other threads
	}

	private void checkCurrentMarkerInSource() {
		int retry;
		try {
			retry = Integer.parseInt( properties.getProperty( Environment.RETRY_MARKER_LOOKUP, "0" ) );
			if (retry < 0) {
				throw new NumberFormatException( );
			}
		}
		catch ( NumberFormatException e ) {
			throw new SearchException("retry_marker_lookup options must be a positive number: "
					+ properties.getProperty( Environment.RETRY_MARKER_LOOKUP ) );
		}
		boolean currentMarkerInSource = false;
		for ( int tried = 0 ; tried <= retry ; tried++ ) {
			//we try right away the first time
			if ( tried > 0) {
				try {
					Thread.sleep( TimeUnit.SECONDS.toMillis( 5 ) );
				}
				catch ( InterruptedException e ) {
					//continue
				}
			}
			currentMarkerInSource =
					new File( sourceIndexDir, "current1" ).exists()
					|| new File( sourceIndexDir, "current2" ).exists();
			if ( currentMarkerInSource ) {
				break;
			}
		}
		if ( !currentMarkerInSource ) {
			throw new IllegalStateException( "No current marker in source directory. Has the master being started once already?" );
		}
	}

	public void start() {
		int readCurrentState = current; //Unneeded value, but ensure visibility of state protected by memory barrier
		int currentToBe = 0;
		try {
			directory1 = DirectoryProviderHelper.createFSIndex( new File( indexDir, "1" ), properties );
			directory2 = DirectoryProviderHelper.createFSIndex( new File( indexDir, "2" ), properties );
			File currentMarker = new File( indexDir, "current1" );
			File current2Marker = new File( indexDir, "current2" );
			if ( currentMarker.exists() ) {
				currentToBe = 1;
				if ( current2Marker.exists() ) {
					current2Marker.delete(); //TODO or throw an exception?
				}
			}
			else if ( current2Marker.exists() ) {
				currentToBe = 2;
			}
			else {
				//no default
				log.debug( "Setting directory 1 as current" );
				currentToBe = 1;
				File destinationFile = new File( indexDir, Integer.valueOf( currentToBe ).toString() );
				int sourceCurrent;
				if ( new File( sourceIndexDir, "current1" ).exists() ) {
					sourceCurrent = 1;
				}
				else if ( new File( sourceIndexDir, "current2" ).exists() ) {
					sourceCurrent = 2;
				}
				else {
					throw new SearchException( "No current file marker found in source directory: " + sourceIndexDir.getPath() );
				}
				try {
					FileHelper.synchronize(
							new File( sourceIndexDir, String.valueOf( sourceCurrent ) ),
							destinationFile, true, copyChunkSize
					);
				}
				catch ( IOException e ) {
					throw new SearchException( "Unable to synchronize directory: " + indexName, e );
				}
				if ( !currentMarker.createNewFile() ) {
					throw new SearchException( "Unable to create the directory marker file: " + indexName );
				}
			}
			log.debug( "Current directory: {}", currentToBe );
		}
		catch ( IOException e ) {
			throw new SearchException( "Unable to initialize index: " + directoryProviderName, e );
		}
		task = new TriggerTask( sourceIndexDir, indexDir );
		long period = DirectoryProviderHelper.getRefreshPeriod( properties, directoryProviderName );
		timer.scheduleAtFixedRate( task, period, period );
		this.current = currentToBe;
	}

	public FSDirectory getDirectory() {
		int readState = current;// to have the read consistent in the next two "if"s.
		if ( readState == 1 ) {
			return directory1;
		}
		else if ( readState == 2 ) {
			return directory2;
		}
		else {
			throw new AssertionFailure( "Illegal current directory: " + readState );
		}
	}

	@Override
	public boolean equals(Object obj) {
		// this code is actually broken since the value change after initialize call
		// but from a practical POV this is fine since we only call this method
		// after initialize call
		if ( obj == this ) {
			return true;
		}
		if ( obj == null || !( obj instanceof FSSlaveDirectoryProvider ) ) {
			return false;
		}
		FSSlaveDirectoryProvider other = ( FSSlaveDirectoryProvider ) obj;
		//need to break memory barriers on both instances:
		@SuppressWarnings("unused")
		int readCurrentState = this.current; //unneeded value, but ensure visibility of indexName
		readCurrentState = other.current; //another unneeded value, but ensure visibility of indexName
		return indexName.equals( other.indexName );
	}

	@Override
	public int hashCode() {
		// this code is actually broken since the value change after initialize call
		// but from a practical POV this is fine since we only call this method
		// after initialize call
		@SuppressWarnings("unused")
		int readCurrentState = current; //unneded value, but ensure visibility of indexName
		int hash = 11;
		return 37 * hash + indexName.hashCode();
	}

	class TriggerTask extends TimerTask {

		private final ExecutorService executor;
		private final CopyDirectory copyTask;

		public TriggerTask(File sourceIndexDir, File destination) {
			executor = Executors.newSingleThreadExecutor();
			copyTask = new CopyDirectory( sourceIndexDir, destination );
		}

		public void run() {
			if ( copyTask.inProgress.compareAndSet( false, true ) ) {
				executor.execute( copyTask );
			}
			else {
				if ( log.isTraceEnabled() ) {
					@SuppressWarnings("unused")
					int unneeded = current;//ensure visibility of indexName in Timer threads.
					log.trace( "Skipping directory synchronization, previous work still in progress: {}", indexName );
				}
			}
		}
		
		public void stop() {
			executor.shutdownNow();
		}
	}

	class CopyDirectory implements Runnable {
		private final File source;
		private final File destination;
		private final AtomicBoolean inProgress = new AtomicBoolean( false );

		public CopyDirectory(File sourceIndexDir, File destination) {
			this.source = sourceIndexDir;
			this.destination = destination;
		}

		public void run() {
			long start = System.nanoTime();
			try {
				File sourceFile = determineCurrentSourceFile();
				if ( sourceFile == null ) {
					log.warn( "Unable to determine current in source directory, will try again during the next synchronization" );
					return;
				}

				// check whether a copy is needed at all
				File currentDestinationFile = new File( destination, Integer.valueOf( current ).toString() );
				try {
					if ( FileHelper.areInSync( sourceFile, currentDestinationFile ) ) {
						if ( log.isTraceEnabled() ) {
							log.trace( "Source and destination directory are in sync. No copying required." );
						}
						return;
					}
				}
				catch ( IOException ioe ) {
					log.warn( "Unable to compare {} with {}.", sourceFile.getName(), currentDestinationFile.getName() );
				}

				// copy is required
				int oldIndex = current;
				int index = oldIndex == 1 ? 2 : 1;
				File destinationFile = new File( destination, Integer.valueOf( index ).toString() );
				try {
					log.trace( "Copying {} into {}", sourceFile, destinationFile );
					FileHelper.synchronize( sourceFile, destinationFile, true, copyChunkSize );
					current = index;
					log.trace( "Copy for {} took {} ms", indexName, TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - start ) );
				}
				catch ( IOException e ) {
					//don't change current
					log.error( "Unable to synchronize " + indexName, e );
					return;
				}
				if ( !new File( indexName, "current" + oldIndex ).delete() ) {
					log.warn( "Unable to remove previous marker file in " + indexName );
				}
				try {
					new File( indexName, "current" + index ).createNewFile();
				}
				catch ( IOException e ) {
					log.warn( "Unable to create current marker file in " + indexName, e );
				}
			}
			finally {
				inProgress.set( false );
			}
		}

		/**
		 * @return Return a file to the currently active source directory. Tests for the files "current1" and
		 *         "current2" in order to determine which is the current directory. If there marker file does not exists
		 *         <code>null</code> is returned.
		 */
		private File determineCurrentSourceFile() {
			File sourceFile = null;
			if ( new File( source, "current1" ).exists() ) {
				sourceFile = new File( source, "1" );
			}
			else if ( new File( source, "current2" ).exists() ) {
				sourceFile = new File( source, "2" );
			}
			return sourceFile;
		}
	}

	public void stop() {
		@SuppressWarnings("unused")
		int readCurrentState = current; //unneded value, but ensure visibility of state protected by memory barrier
		timer.cancel();
		task.stop();
		try {
			directory1.close();
		}
		catch ( Exception e ) {
			log.error( "Unable to properly close Lucene directory {}" + directory1.getFile(), e );
		}
		try {
			directory2.close();
		}
		catch ( Exception e ) {
			log.error( "Unable to properly close Lucene directory {}" + directory2.getFile(), e );
		}
	}
}
