package org.hibernate.search.test.configuration;

import java.lang.annotation.ElementType;

import org.apache.lucene.search.DefaultSimilarity;
import org.apache.solr.analysis.EnglishPorterFilterFactory;
import org.apache.solr.analysis.GermanStemFilterFactory;
import org.apache.solr.analysis.LowerCaseFilterFactory;
import org.apache.solr.analysis.NGramFilterFactory;
import org.apache.solr.analysis.StandardTokenizerFactory;
import org.hibernate.search.annotations.Factory;
import org.hibernate.search.annotations.FilterCacheModeType;
import org.hibernate.search.annotations.Index;
import org.hibernate.search.annotations.Resolution;
import org.hibernate.search.annotations.Store;
import org.hibernate.search.bridge.builtin.LongBridge;
import org.hibernate.search.cfg.ConcatStringBridge;
import org.hibernate.search.cfg.SearchMapping;

public class ProgrammaticSearchMappingFactory {
		
	@Factory
	public SearchMapping build() {
		SearchMapping mapping = new SearchMapping();

		mapping.fullTextFilterDef("security", SecurityFilterFactory.class).cache(FilterCacheModeType.INSTANCE_ONLY)
				.analyzerDef( "ngram", StandardTokenizerFactory.class )
					.filter( LowerCaseFilterFactory.class )
					.filter( NGramFilterFactory.class )
						.param( "minGramSize", "3" )
						.param( "maxGramSize", "3" )
				.analyzerDef( "en", StandardTokenizerFactory.class )
					.filter( LowerCaseFilterFactory.class )
					.filter( EnglishPorterFilterFactory.class )
				.analyzerDef( "de", StandardTokenizerFactory.class )
					.filter( LowerCaseFilterFactory.class )
					.filter( GermanStemFilterFactory.class )
				.entity( Address.class )
					.indexed()
					.similarity( DefaultSimilarity.class )
					.boost( 2 )
					.property( "addressId", ElementType.FIELD ).documentId().name( "id" )
					.property("lastUpdated", ElementType.FIELD)
						.field().name("last-updated")
								.analyzer("en").store(Store.YES)
								.calendarBridge(Resolution.DAY)
					.property("dateCreated", ElementType.FIELD)
						.field().name("date-created").index(Index.TOKENIZED)
								.analyzer("en").store( Store.YES )
								.dateBridge(Resolution.DAY)
					.property("owner", ElementType.FIELD)
						.field()
					.property( "street1", ElementType.FIELD )
						.field()
						.field().name( "street1_ngram" ).analyzer( "ngram" )
						.field()
							.name( "street1_abridged" )
							.bridge( ConcatStringBridge.class ).param( ConcatStringBridge.SIZE, "4" )
					.property( "street2", ElementType.METHOD )
						.field().name( "idx_street2" ).store( Store.YES ).boost( 2 )
				.entity(ProvidedIdEntry.class).indexed()
						.providedId().name("providedidentry").bridge(LongBridge.class)
						.property("name", ElementType.FIELD)
							.field().name("providedidentry.name").analyzer("en").index(Index.TOKENIZED).store(Store.YES)
						.property("blurb", ElementType.FIELD)
							.field().name("providedidentry.blurb").analyzer("en").index(Index.TOKENIZED).store(Store.YES)
						.property("age", ElementType.FIELD)
							.field().name("providedidentry.age").analyzer("en").index(Index.TOKENIZED).store(Store.YES)
				.entity(ProductCatalog.class).indexed()
					.similarity( DefaultSimilarity.class )
					.boost( 2 )
					.property( "id", ElementType.FIELD ).documentId().name( "id" )
					.property("name", ElementType.FIELD)
						.field().name("productCatalogName").index(Index.TOKENIZED).analyzer("en").store(Store.YES)
					.property("items", ElementType.FIELD)
						.indexEmbedded()
				.entity(Item.class)
					.property("description", ElementType.FIELD)
						.field().name("description").analyzer("en").index(Index.TOKENIZED).store(Store.YES)
					.property("productCatalog", ElementType.FIELD)
						.containedIn()
				.entity(DynamicBoostedDescLibrary.class)
					.indexed()
					.dynamicBoost(CustomBoostStrategy.class)
					.property("libraryId", ElementType.FIELD)
						.documentId().name("id")
					.property("name", ElementType.FIELD)
						.field().store(Store.YES)
						.dynamicBoost(CustomFieldBoostStrategy.class)
				.entity(Departments.class)
					.classBridge(CatDeptsFieldsClassBridge.class)
						.name("branchnetwork")
						.index(Index.TOKENIZED)
						.store(Store.YES)
						.param("sepChar", " ")
					.classBridge(EquipmentType.class)
						.name("equiptype")
						.index(Index.TOKENIZED)
						.store(Store.YES)
							.param("C", "Cisco")
							.param("D", "D-Link")
							.param("K", "Kingston")
							.param("3", "3Com")
					  .indexed()
					.property("deptsId", ElementType.FIELD)
						.documentId().name("id")
					.property("branchHead", ElementType.FIELD)
						.field().store(Store.YES)
					.property("network", ElementType.FIELD)
						.field().store(Store.YES)
					.property("branch", ElementType.FIELD)
						.field().store(Store.YES)
					.property("maxEmployees", ElementType.FIELD)
						.field().index(Index.UN_TOKENIZED).store(Store.YES)
				.entity( BlogEntry.class ).indexed()
					.property( "title", ElementType.METHOD )
						.field()
					.property( "description", ElementType.METHOD )
						.field()
					.property( "language", ElementType.METHOD )
						.analyzerDiscriminator(BlogEntry.BlogLangDiscriminator.class)
					.property("dateCreated", ElementType.METHOD)
						.field()
							.name("blog-entry-created")
								.analyzer("en")
								.store(Store.YES)
								.dateBridge(Resolution.DAY);
		return mapping;

	}
}
