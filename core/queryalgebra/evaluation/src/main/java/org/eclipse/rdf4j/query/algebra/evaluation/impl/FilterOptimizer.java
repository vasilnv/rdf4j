/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 *******************************************************************************/
package org.eclipse.rdf4j.query.algebra.evaluation.impl;

import java.util.Set;

import org.eclipse.rdf4j.query.algebra.And;
import org.eclipse.rdf4j.query.algebra.Difference;
import org.eclipse.rdf4j.query.algebra.Distinct;
import org.eclipse.rdf4j.query.algebra.EmptySet;
import org.eclipse.rdf4j.query.algebra.Extension;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.Intersection;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.LeftJoin;
import org.eclipse.rdf4j.query.algebra.Order;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.Reduced;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Union;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.algebra.helpers.collectors.VarNameCollector;

/**
 * Optimizes a query model by pushing {@link Filter}s as far down in the model tree as possible.
 * <p>
 * To make the first optimization succeed more often it splits filters which contains {@link And} conditions.
 *
 * <code>
 * SELECT * WHERE {
 * ?s ?p ?o .
 * ?s ?p ?o2  .
 * FILTER(?o > '2'^^xsd:int && ?o2 < '4'^^xsd:int)
 * }
 * </code> May be more efficient when decomposed into <code>
 * SELECT * WHERE {
 * ?s ?p ?o .
 * FILTER(?o > '2'^^xsd:int)
 * ?s ?p ?o2  .
 * FILTER(?o2 < '4'^^xsd:int)
 * }
 * </code>
 * <p>
 * Then it optimizes a query model by merging adjacent {@link Filter}s. e.g. <code>
 * SELECT * WHERE {
 * ?s ?p ?o .
 * FILTER(?o > 2) .
 * FILTER(?o < 4) .
 * }
 * </code> may be merged into <code>
 * SELECT * WHERE {
 * ?s ?p ?o .
 * FILTER(?o > 2 && ?o < 4) . }
 * </code>
 * <p>
 * This optimization allows for sharing evaluation costs in the future and removes an iterator. This is done as a second
 * step to not break the first optimization. In the case that the splitting was done but did not help it is now undone.
 *
 * @author Arjohn Kampman
 * @author Jerven Bolleman
 *
 * @deprecated since 4.1.0. Use {@link org.eclipse.rdf4j.query.algebra.evaluation.optimizer.FilterOptimizer} instead.
 */
@Deprecated(forRemoval = true, since = "4.1.0")
public class FilterOptimizer extends org.eclipse.rdf4j.query.algebra.evaluation.optimizer.FilterOptimizer
		implements QueryOptimizer {

	@Deprecated(forRemoval = true, since = "4.1.0")
	protected static class FilterFinder extends AbstractQueryModelVisitor<RuntimeException> {

		protected final TupleExpr tupleExpr;

		public FilterFinder(TupleExpr tupleExpr) {
			this.tupleExpr = tupleExpr;
		}

		@Override
		public void meet(Filter filter) {
			super.meet(filter);
			FilterRelocator.relocate(filter);
		}
	}

	@Deprecated(forRemoval = true, since = "4.1.0")
	protected static class FilterRelocator extends AbstractQueryModelVisitor<RuntimeException> {

		public static void relocate(Filter filter) {
			filter.visit(new FilterRelocator(filter));
		}

		protected final Filter filter;

		protected final Set<String> filterVars;

		public FilterRelocator(Filter filter) {
			this.filter = filter;
			filterVars = VarNameCollector.process(filter.getCondition());
		}

		@Override
		protected void meetNode(QueryModelNode node) {
			// By default, do not traverse
			assert node instanceof TupleExpr;
			relocate(filter, (TupleExpr) node);
		}

		@Override
		public void meet(Join join) {
			if (join.getLeftArg().getBindingNames().containsAll(filterVars)) {
				// All required vars are bound by the left expr
				join.getLeftArg().visit(this);
			} else if (join.getRightArg().getBindingNames().containsAll(filterVars)) {
				// All required vars are bound by the right expr
				join.getRightArg().visit(this);
			} else {
				relocate(filter, join);
			}
		}

		@Override
		public void meet(StatementPattern sp) {
			if (sp.getBindingNames().containsAll(filterVars)) {
				// All required vars are bound by the left expr
				relocate(filter, sp);
			}
		}

		@Override
		public void meet(LeftJoin leftJoin) {
			if (leftJoin.getLeftArg().getBindingNames().containsAll(filterVars)) {
				leftJoin.getLeftArg().visit(this);
			} else {
				relocate(filter, leftJoin);
			}
		}

		@Override
		public void meet(Union union) {
			Filter clone = new Filter();
			clone.setCondition(filter.getCondition().clone());

			relocate(filter, union.getLeftArg());
			relocate(clone, union.getRightArg());

			FilterRelocator.relocate(filter);
			FilterRelocator.relocate(clone);
		}

		@Override
		public void meet(Difference node) {
			Filter clone = new Filter();
			clone.setCondition(filter.getCondition().clone());

			relocate(filter, node.getLeftArg());
			relocate(clone, node.getRightArg());

			FilterRelocator.relocate(filter);
			FilterRelocator.relocate(clone);
		}

		@Override
		public void meet(Intersection node) {
			Filter clone = new Filter();
			clone.setCondition(filter.getCondition().clone());

			relocate(filter, node.getLeftArg());
			relocate(clone, node.getRightArg());

			FilterRelocator.relocate(filter);
			FilterRelocator.relocate(clone);
		}

		@Override
		public void meet(Extension node) {
			if (node.getArg().getBindingNames().containsAll(filterVars)) {
				node.getArg().visit(this);
			} else {
				relocate(filter, node);
			}
		}

		@Override
		public void meet(EmptySet node) {
			if (filter.getParentNode() != null) {
				// Remove filter from its original location
				filter.replaceWith(filter.getArg());
			}
		}

		@Override
		public void meet(Filter filter) {
			// Filters are commutative
			filter.getArg().visit(this);
		}

		@Override
		public void meet(Distinct node) {
			node.getArg().visit(this);
		}

		@Override
		public void meet(Order node) {
			node.getArg().visit(this);
		}

		@Override
		public void meet(QueryRoot node) {
			node.getArg().visit(this);
		}

		@Override
		public void meet(Reduced node) {
			node.getArg().visit(this);
		}

		protected void relocate(Filter filter, TupleExpr newFilterArg) {
			if (filter.getArg() != newFilterArg) {
				if (filter.getParentNode() != null) {
					// Remove filter from its original location
					filter.replaceWith(filter.getArg());
				}

				// Insert filter at the new location
				newFilterArg.replaceWith(filter);
				filter.setArg(newFilterArg);
			}
		}
	}

	@Deprecated(forRemoval = true, since = "4.1.0")
	protected static class MergeFilterFinder extends AbstractQueryModelVisitor<RuntimeException> {

		@Override
		public void meet(Filter filter) {
			super.meet(filter);
			if (filter.getParentNode() instanceof Filter) {

				Filter parentFilter = (Filter) filter.getParentNode();
				QueryModelNode grandParent = parentFilter.getParentNode();
				ValueExpr parentCondition = parentFilter.getCondition();
				ValueExpr thisCondition = filter.getCondition();
				And merge = new And(parentCondition, thisCondition);
				filter.setCondition(merge);
				grandParent.replaceChildNode(parentFilter, filter);
			}
		}
	}

	@Deprecated(forRemoval = true, since = "4.1.0")
	protected static class DeMergeFilterFinder extends AbstractQueryModelVisitor<RuntimeException> {

		@Override
		public void meet(Filter filter) {
			super.meet(filter);
			if (filter.getCondition() instanceof And) {

				And and = (And) filter.getCondition();
				ValueExpr left = and.getLeftArg();
				ValueExpr right = and.getRightArg();
				filter.setCondition(left);
				Filter newFilter = new Filter(filter.getArg(), right);
				filter.replaceChildNode(filter.getArg(), newFilter);
				meet(newFilter);
				meet(filter);
			}
		}
	}

}
