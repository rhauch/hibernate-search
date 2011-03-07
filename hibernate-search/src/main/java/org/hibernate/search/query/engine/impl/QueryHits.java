/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2010-2011, Red Hat, Inc. and/or its affiliates or third-party contributors as
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
package org.hibernate.search.query.engine.impl;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.FieldSelector;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TimeLimitingCollector;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopDocsCollector;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.Weight;

import org.hibernate.QueryTimeoutException;
import org.hibernate.search.SearchException;
import org.hibernate.search.query.FacetCollector;
import org.hibernate.search.query.engine.spi.TimeoutManager;
import org.hibernate.search.query.facet.FacetRequest;
import org.hibernate.search.query.facet.FacetResult;
import org.hibernate.search.query.fieldcache.FieldCacheCollector;
import org.hibernate.search.query.fieldcache.FieldCacheCollectorFactory;

/**
 * A helper class which gives access to the current query and its hits. This class will dynamically
 * reload the underlying {@code TopDocs} if required.
 *
 * @author Hardy Ferentschik
 * @author Sanne Grinovero <sanne@hibernate.org> (C) 2011 Red Hat Inc.
 */
public class QueryHits {

	private static final int DEFAULT_TOP_DOC_RETRIEVAL_SIZE = 100;

	private final org.apache.lucene.search.Query preparedQuery;
	private final IndexSearcherWithPayload searcher;
	private final Filter filter;
	private final Sort sort;
	private final Map<String, FacetRequest> facetRequests;
	private final TimeoutManagerImpl timeoutManager;

	private int totalHits;
	private TopDocs topDocs;
	private Map<String, FacetResult> facetResultMap;
	private List<FacetCollector> facetCollectors;

	private final boolean enableFieldCacheOnClassName;

	/**
	 * If enabled, after hits collection it will contain the Class instances for each hit
	 */
	private FieldCacheCollector<String> classTypeCollector;

	/**
	 * If enabled, a Collector will collect values from the primary keys
	 */
	private final FieldCacheCollectorFactory idFieldCollectorFactory;
	private FieldCacheCollector idFieldCollector;

	public QueryHits(IndexSearcherWithPayload searcher,
					 org.apache.lucene.search.Query preparedQuery,
					 Filter filter,
					 Sort sort,
					 TimeoutManagerImpl timeoutManager,
					 Map<String, FacetRequest> facetRequests,
					 boolean enableFieldCacheOnTypes,
					 FieldCacheCollectorFactory idFieldCollector)
			throws IOException {
		this(
				searcher, preparedQuery, filter, sort, DEFAULT_TOP_DOC_RETRIEVAL_SIZE, timeoutManager, facetRequests,
				enableFieldCacheOnTypes, idFieldCollector
		);
	}

	public QueryHits(IndexSearcherWithPayload searcher,
					 org.apache.lucene.search.Query preparedQuery,
					 Filter filter,
					 Sort sort,
					 Integer n,
					 TimeoutManagerImpl timeoutManager,
					 Map<String, FacetRequest> facetRequests,
					 boolean enableFieldCacheOnTypes,
					 FieldCacheCollectorFactory idFieldCollector)
			throws IOException {
		this.timeoutManager = timeoutManager;
		this.preparedQuery = preparedQuery;
		this.searcher = searcher;
		this.filter = filter;
		this.sort = sort;
		this.facetRequests = facetRequests;
		this.enableFieldCacheOnClassName = enableFieldCacheOnTypes;
		this.idFieldCollectorFactory = idFieldCollector;
		updateTopDocs( n );
		this.totalHits = topDocs.totalHits;
	}

	public Document doc(int index) throws IOException {
		return searcher.getSearcher().doc( docId( index ) );
	}

	public Document doc(int index, FieldSelector selector) throws IOException {
		return searcher.getSearcher().doc( docId( index ), selector );
	}

	public ScoreDoc scoreDoc(int index) throws IOException {
		if ( index >= totalHits ) {
			throw new SearchException( "Not a valid ScoreDoc index: " + index );
		}

		// TODO - Is there a better way to get more TopDocs? Get more or less?
		if ( index >= topDocs.scoreDocs.length ) {
			updateTopDocs( 2 * index );
		}
		//if the refresh timed out, raise an exception
		if ( timeoutManager.isTimedOut() && index >= topDocs.scoreDocs.length ) {
			throw new QueryTimeoutException(
					"Timeout period exceeded. Cannot load document: " + index,
					(SQLException) null,
					preparedQuery.toString()
			);
		}
		return topDocs.scoreDocs[index];
	}

	public int docId(int index) throws IOException {
		return scoreDoc( index ).doc;
	}

	public float score(int index) throws IOException {
		return scoreDoc( index ).score;
	}

	public Explanation explain(int index) throws IOException {
		final Explanation explanation = searcher.getSearcher().explain( preparedQuery, docId( index ) );
		timeoutManager.isTimedOut();
		return explanation;
	}

	public int getTotalHits() {
		return totalHits;
	}

	public TopDocs getTopDocs() {
		return topDocs;
	}

	public Map<String, FacetResult> getFacetResults() {
		if ( facetRequests == null || facetRequests.size() == 0 ) {
			return Collections.emptyMap();
		}
		return facetResultMap;
	}

	/**
	 * @param n the number of {@code TopDoc}s to retrieve. The actual retrieved number of {@code TopDoc}s is n or the
	 * total number of documents if {@code n > maxDoc}
	 *
	 * @throws IOException in case a search exception occurs
	 */
	private void updateTopDocs(int n) throws IOException {
		int totalMaxDocs = searcher.getSearcher().maxDoc();
		final int maxDocs = Math.min( n, totalMaxDocs );
		final Weight weight = preparedQuery.weight( searcher.getSearcher() );

		TopDocsCollector<?> topDocCollector = createTopDocCollector( maxDocs, weight );
		Collector collector = decorateWithTimeOutCollector( topDocCollector );

		boolean timeoutNow = isImmediateTimeout();
		if ( !timeoutNow ) {
			try {
				collector = optionallyEnableFieldCacheOnTypes( collector, totalMaxDocs, maxDocs );
				collector = optionallyEnableFieldCacheOnIds( collector, totalMaxDocs, maxDocs );
				collector = optionallyFacetingCollectors( collector );
				searcher.getSearcher().search( weight, filter, collector );
			}
			catch ( TimeLimitingCollector.TimeExceededException e ) {
				//we have reached the time limit and stopped before the end
				//TimeoutManager.isTimedOut should be above that limit but set if for safety
				timeoutManager.forceTimedOut();
				topDocs = topDocCollector.topDocs();
			}
		}

		// update top docs
		topDocs = topDocCollector.topDocs();

		// if we were collecting facet data we have to update our instance state
		if ( facetCollectors != null && !facetCollectors.isEmpty() ) {
			facetResultMap = new HashMap<String, FacetResult>();
			for ( FacetCollector facetCollector : facetCollectors ) {
				FacetResult facetResult = facetCollector.getFacetResult();
				facetResultMap.put( facetResult.getName(), facetResult );
			}
		}
		timeoutManager.isTimedOut();
	}

	private Collector optionallyFacetingCollectors(Collector collector) {
		if ( facetRequests == null || facetRequests.isEmpty() ) {
			return collector;
		}
		facetCollectors = new ArrayList<FacetCollector>();
		Collector nextInChain = collector;
		for ( FacetRequest entry : facetRequests.values() ) {
			FacetCollector facetCollector = new FacetCollector( nextInChain, entry );
			nextInChain = facetCollector;
			facetCollectors.add( facetCollector );
		}

		return facetCollectors.get( facetCollectors.size() - 1 );
	}

	private boolean isImmediateTimeout() {
		boolean timeoutAt0 = false;
		if ( timeoutManager.getType() == TimeoutManager.Type.LIMIT ) {
			final Long timeoutLeft = timeoutManager.getTimeoutLeftInMilliseconds();
			if ( timeoutLeft != null ) {
				if ( timeoutLeft == 0l ) {
					if ( timeoutManager.getType() == TimeoutManager.Type.LIMIT && timeoutManager.isTimedOut() ) {
						timeoutManager.forceTimedOut();
						timeoutAt0 = true;
					}
				}
			}
			else {
				if ( timeoutManager.isTimedOut() ) {
					timeoutManager.forceTimedOut();
				}
			}
		}
		return timeoutAt0;
	}

	private Collector decorateWithTimeOutCollector(Collector collector) {
		Collector maybeTimeLimitingCollector = collector;
		if ( timeoutManager.getType() == TimeoutManager.Type.LIMIT ) {
			final Long timeoutLeft = timeoutManager.getTimeoutLeftInMilliseconds();
			if ( timeoutLeft != null ) {
				maybeTimeLimitingCollector = new TimeLimitingCollector( collector, timeoutLeft );
			}
		}
		return maybeTimeLimitingCollector;
	}

	private TopDocsCollector<?> createTopDocCollector(int maxDocs, Weight weight) throws IOException {
		TopDocsCollector<?> topCollector;
		if ( sort == null ) {
			topCollector = TopScoreDocCollector.create( maxDocs, !weight.scoresDocsOutOfOrder() );
		}
		else {
			boolean fillFields = true;
			topCollector = TopFieldCollector.create(
					sort,
					maxDocs,
					fillFields,
					searcher.isFieldSortDoTrackScores(),
					searcher.isFieldSortDoMaxScore(),
					!weight.scoresDocsOutOfOrder()
			);
		}
		return topCollector;
	}

	private Collector optionallyEnableFieldCacheOnIds(Collector collector, int totalMaxDocs, int maxDocs) {
		if ( idFieldCollectorFactory != null ) {
			idFieldCollector = idFieldCollectorFactory.createFieldCollector( collector, totalMaxDocs, maxDocs );
			return idFieldCollector;
		}
		return collector;
	}

	private Collector optionallyEnableFieldCacheOnTypes(Collector collector, int totalMaxDocs, int expectedMatchesCount) {
		if ( enableFieldCacheOnClassName ) {
			classTypeCollector = FieldCacheCollectorFactory
					.CLASS_TYPE_FIELDCACHE_COLLECTOR_FACTORY
					.createFieldCollector( collector, totalMaxDocs, expectedMatchesCount );
			return classTypeCollector;
		}
		else {
			return collector;
		}
	}

	public FieldCacheCollector<String> getClassTypeCollector() {
		return classTypeCollector;
	}

	public FieldCacheCollector getIdsCollector() {
		return idFieldCollector;
	}

}
