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

package org.hibernate.search.query.dsl.impl;

import org.hibernate.search.query.facet.FacetRequest;
import org.hibernate.search.query.facet.FacetSortOrder;

/**
 * @author Hardy Ferentschik
 */
class FacetBuildingContext {
	private String name;
	private String fieldName;
	private FacetSortOrder sort = FacetSortOrder.COUNT_DESC;
	private boolean includeZeroCount = true;
	private boolean isRangeQuery = false;

	void setName(String name) {
		this.name = name;
	}

	void setFieldName(String fieldName) {
		this.fieldName = fieldName;
	}

	void setSort(FacetSortOrder sort) {
		this.sort = sort;
	}

	void setIncludeZeroCount(boolean includeZeroCount) {
		this.includeZeroCount = includeZeroCount;
	}

	FacetRequest getFacetRequest() {
		FacetRequest request = null;
		if ( isRangeQuery ) {
			// request = ...
		}
		else {
			request = new DiscreteFacetRequest( name, fieldName );
		}
		request.setSort( sort );
		request.setIncludeZeroCounts( includeZeroCount );
		return request;
	}
}


