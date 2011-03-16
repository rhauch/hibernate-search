/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2011, Red Hat, Inc. and/or its affiliates or third-party contributors as
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

package org.hibernate.search.query.collector;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.Scorer;

import org.hibernate.search.query.dsl.impl.DiscreteFacetRequest;
import org.hibernate.search.query.dsl.impl.FacetRange;
import org.hibernate.search.query.dsl.impl.FacetingRequestImpl;
import org.hibernate.search.query.dsl.impl.RangeFacetRequest;
import org.hibernate.search.query.facet.Facet;
import org.hibernate.search.query.facet.FacetSortOrder;
import org.hibernate.search.query.fieldcache.FieldCacheLoadingType;
import org.hibernate.search.query.fieldcache.FieldLoadingStrategy;

import static org.hibernate.search.util.CollectionHelper.newArrayList;
import static org.hibernate.search.util.CollectionHelper.newHashMap;

/**
 * A custom {@code Collector} used for handling facet requests.
 *
 * @author Hardy Ferentschik
 */
public class FacetCollector extends Collector {
	/**
	 * The next collector in the delegation chain
	 */
	private final Collector delegate;

	/**
	 * Facet request this collector handles
	 */
	private final FacetingRequestImpl facetRequest;

	/**
	 * Used to load field values from the Lucene field cache
	 */
	private final FieldLoadingStrategy fieldLoader;

	/**
	 * A counter mapped to the field name for which it is counting
	 */
	private final FacetCounter facetCounts;

	/**
	 * Flag indicating whether the data structure has been initialised. Initialisation happens on the first call
	 * to {@link #setNextReader(org.apache.lucene.index.IndexReader, int)}.
	 */
	private boolean initialised = false;

	public FacetCollector(Collector delegate, FacetingRequestImpl facetRequest) {
		this.delegate = delegate;
		this.facetRequest = facetRequest;
		this.facetCounts = createFacetCounter( facetRequest );
		fieldLoader = FieldCacheLoadingType.getLoadingStrategy(
				this.facetRequest.getFieldName(), this.facetRequest.getFieldCacheType()
		);
	}

	@Override
	public void setNextReader(IndexReader reader, int docBase) throws IOException {
		if ( !initialised ) {
			initialiseCollector( reader );
		}
		initialiseFieldCaches( reader );
		delegate.setNextReader( reader, docBase );
	}

	@Override
	public void collect(int doc) throws IOException {
		Object value = fieldLoader.collect( doc );
		if ( value != null ) {
			facetCounts.countValue( value );
		}
		delegate.collect( doc );
	}

	@Override
	public void setScorer(Scorer scorer) throws IOException {
		delegate.setScorer( scorer );
	}

	@Override
	public boolean acceptsDocsOutOfOrder() {
		return delegate.acceptsDocsOutOfOrder();
	}

	public String getFacetName() {
		return facetRequest.getName();
	}

	public List<Facet> getFacetList() {
		return createSortedFacetList( facetCounts, facetRequest );
	}

	private List<Facet> createSortedFacetList(FacetCounter counter, FacetingRequestImpl request) {
		List<Facet> facetList = newArrayList();
		int includedFacetCount = 0;
		for ( Map.Entry<String, Integer> countEntry : counter.getCounts().entrySet() ) {
			Facet facet = request.createFacet( countEntry.getKey(), countEntry.getValue() );
			if ( !request.includeZeroCounts() && facet.getCount() == 0 ) {
				continue;
			}
			if ( facetRequest.getMaxNumberOfFacets() > 0 && includedFacetCount == facetRequest.getMaxNumberOfFacets() ) {
				break;
			}
			facetList.add( facet );
			includedFacetCount++;
		}
		Collections.sort( facetList, new FacetComparator( request.getSort() ) );
		return facetList;
	}

	private void initialiseCollector(IndexReader reader) throws IOException {
		// we only need to initialise the counts in case we have to include 0 counts as well
		if ( facetRequest.includeZeroCounts() && facetRequest instanceof DiscreteFacetRequest ) {
			initFacetCounts( reader );
		}
		initialised = true;
	}

	private void initialiseFieldCaches(IndexReader reader) throws IOException {
		fieldLoader.loadNewCacheValues( reader );
	}

	private <N extends Number> FacetCounter createFacetCounter(FacetingRequestImpl request) {
		if ( request instanceof DiscreteFacetRequest ) {
			return new SimpleFacetCounter();
		}
		else if ( request instanceof RangeFacetRequest ) {
			@SuppressWarnings("unchecked")
			RangeFacetRequest<N> rangeFacetRequest = (RangeFacetRequest<N>) request;
			return new RangeFacetCounter<N>( rangeFacetRequest );
		}
		else {
			throw new IllegalArgumentException( "Unsupported cache type" );
		}
	}

	private void initFacetCounts(IndexReader reader) throws IOException {
		String fieldName = facetRequest.getFieldName();
		// term are enumerated by field name and within field names by term value
		TermEnum terms = reader.terms( new Term( fieldName, "" ) );
		try {
			while ( fieldName.equals( terms.term().field() ) ) {
				String fieldValue = terms.term().text();
				facetCounts.initCount( fieldValue );
				if ( !terms.next() ) {
					break;
				}
			}
		}
		finally {
			terms.close();
		}
	}

	static public class FacetComparator implements Comparator<Facet> {
		private final FacetSortOrder sortOder;

		public FacetComparator(FacetSortOrder sortOrder) {
			this.sortOder = sortOrder;
		}

		public int compare(Facet facet1, Facet facet2) {
			if ( FacetSortOrder.COUNT_ASC.equals( sortOder ) ) {
				return facet1.getCount() - facet2.getCount();
			}
			else if ( FacetSortOrder.COUNT_DESC.equals( sortOder ) ) {
				return facet2.getCount() - facet1.getCount();
			}
			else {
				return facet1.getValue().compareTo( facet2.getValue() );
			}
		}
	}

	static public abstract class FacetCounter {
		private Map<String, Integer> counts = newHashMap();

		Map<String, Integer> getCounts() {
			return counts;
		}

		void initCount(String value) {
			if ( !counts.containsKey( value ) ) {
				counts.put( value, 0 );
			}
		}

		void incrementCount(String value) {
			if ( !counts.containsKey( value ) ) {
				counts.put( value, 1 );
			}
			else {
				counts.put( value, counts.get( value ) + 1 );
			}
		}

		abstract void countValue(Object value);
	}

	static class SimpleFacetCounter extends FacetCounter {
		void countValue(Object value) {
			incrementCount( (String) value );
		}
	}

	static class RangeFacetCounter<T> extends FacetCounter {
		private final List<FacetRange<T>> ranges;

		RangeFacetCounter(RangeFacetRequest<T> request) {
			this.ranges = request.getFacetRangeList();
			for ( FacetRange<T> range : ranges ) {
				initCount( range.getRangeString() );
			}
		}

		void countValue(Object value) {
			for ( FacetRange<T> range : ranges ) {
				if ( range.isInRange( (T) value ) ) {
					incrementCount( range.getRangeString() );
				}
			}
		}
	}
}
