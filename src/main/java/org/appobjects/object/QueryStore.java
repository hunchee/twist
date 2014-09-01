/**
 *
 * Copyright (c) 2014 Kerby Martino and others. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.appobjects.object;

import com.google.appengine.api.datastore.*;
import com.google.common.base.Preconditions;
import org.appobjects.serializer.ObjectSerializer;
import org.appobjects.util.BoundedIterator;
import org.appobjects.util.Pair;

import java.util.*;

/**
 * Created by kerby on 4/27/14.
 */
public class QueryStore extends AbstractStore {

    public QueryStore(DatastoreService ds, ObjectSerializer serializer) {
        super(ds, serializer);
    }

    /**
     * Check whether entity with the given properties exist
     *
     * @param props entity to extract properties from
     * @return
     */
    protected boolean containsEntityWithFieldLike(String kind, Entity props){
        Preconditions.checkNotNull(props, "Entity cannot be null");
        boolean contains = false;
        Map<String,Object> m = props.getProperties();
        Transaction tx = _ds.beginTransaction();
        try {
            Iterator<String> it = m.keySet().iterator();
            Query q = new Query(kind);
            while (it.hasNext()){ // build the query
                String propName = it.next();
                Query.Filter filter = new Query.FilterPredicate(propName,
                        Query.FilterOperator.EQUAL, m.get(propName));
                Query.Filter prevFilter = q.getFilter();
                // Note that the QueryStore object is immutable
                q = new Query(kind).setFilter(prevFilter).setFilter(filter);
                q = q.setKeysOnly();
            }
            PreparedQuery pq = _ds.prepare(q);
            int c = pq.countEntities(FetchOptions.Builder.withDefaults());
            if (c != 0)
                contains = true;
            tx.commit();
        } catch (Exception e) {
            tx.rollback();
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
        }
        return contains;
    }


    public Iterator<Map<String,Object>> query(String kind,
            Map<String, Pair<Query.FilterOperator, Object>> filters,
            Map<String, Query.SortDirection> sorts, Integer _numToSkip, Integer _max){
        if (filters == null){
            filters = new HashMap<String, Pair<Query.FilterOperator, Object>>();
        }
        /**
         * Map of fields and its matching filter operator and compare valueType
         */
        Iterator<Map<String,Object>> it = null;
        try {
            if (sorts == null){
                sorts = new HashMap<String, Query.SortDirection>();
            }
            final Iterator<Entity> eit = querySortedLike(kind, filters, sorts);
            it = new Iterator<Map<String,Object>>() {
                public void remove() {
                    eit.remove();
                }
                public Map<String,Object> next() {
                    Entity e = eit.next();
                    return null; //TODO!
                    //return EntityMapper.createMapObjectFromEntity(e);
                }
                public boolean hasNext() {
                    return eit.hasNext();
                }
            };
        } catch (Exception e) {
            // TODO Handle exception
            e.printStackTrace();
            it = null;
        } finally {

        }
        if (it == null){
            LOG.debug("Returning null iterator");
        }
        if (_max != null){
            if (_numToSkip != null){
                return new BoundedIterator<Map<String,Object>>(_numToSkip, _max, it);
            } else {
                return new BoundedIterator<Map<String,Object>>(0, _max, it);
            }
        }
        //List asList = Lists.newArrayList(it);
        return it;
    }

    /**
     * Builds a query filter from the given <code>Entity</code> property names and values and add sorting from
     * <code>Map</code> sorts.
     *
     * <br>
     * <code>
     *  Query q = new Query(kind).filter(f1).filter(f2).filter(fn).addSort(s).addSort(sn); // and so forth
     * </code>
     *
     * FIXME - Fix query object filter/sort getting wiped out
     *
     * @param sorts {@code Map} of sort direction
     * @return
     */
    public Iterator<Entity> querySortedLike(String kind,
            Map<String, Pair<Query.FilterOperator, Object>> query, Map<String, Query.SortDirection> sorts){
        Preconditions.checkNotNull(query, "Query object can't be null");
        Preconditions.checkNotNull(sorts, "Sort can't be null");
        LOG.debug("Query map="+query.toString());

        PreparedQuery pq = null;

        // Sort
        Iterator<Map.Entry<String, Query.SortDirection>> sortIterator = sorts.entrySet().iterator();
        Query q = new Query(kind);
        List<Query.Filter> subFilters = new ArrayList<Query.Filter>();
        if (!query.isEmpty()){
            // Apply filters and sorting for fields given in the filter query
            for (String propName : query.keySet()){
                LOG.debug("Filter Property name="+propName);
                Pair<Query.FilterOperator, Object> filterAndValue = query.get(propName);
                Query.FilterOperator operator = filterAndValue.getFirst();
                Object value = filterAndValue.getSecond();
                Query.Filter _filter = null;
                if (propName.equals(KEY_RESERVED_PROPERTY)){
                    _filter = new Query.FilterPredicate(Entity.KEY_RESERVED_PROPERTY, operator,
                            KeyStructure.createKey(kind, String.valueOf(value)));
                } else {
                    _filter = new Query.FilterPredicate(propName, operator, value);
                }
                Query.Filter prevFilter = q.getFilter();
                if (sorts.get(propName) != null){
                    q = new Query(kind).setFilter(prevFilter).setFilter(_filter)
                            .addSort(propName, sorts.get(propName));
                    sorts.remove(propName); // remove it
                } else {
                    q = new Query(kind).setFilter(prevFilter).setFilter(_filter);
                }
                subFilters.add(_filter);
            }
        } else if (query == null || query.isEmpty()){
            while(sortIterator.hasNext()){
                Map.Entry<String, Query.SortDirection> sort = sortIterator.next();
                q = new Query(kind).addSort(sort.getKey(), sort.getValue());
            }
        }
        pq = _ds.prepare(q);
        Iterator<Entity> res = pq.asIterator();
        return res;
    }



    /**
     * Check whether entity with the given properties exist having
     * field valueType compared with the filter operator

     * @return
     */
    protected boolean containsEntityLike(String kind, Map<String, Pair<Query.FilterOperator, Object>> query){
        Preconditions.checkNotNull(query, "QueryStore object cannot be null");
        boolean contains = false;

        Preconditions.checkNotNull(query, "Null query object");

        Transaction tx = _ds.beginTransaction();
        try {
            Query q = new Query(kind);
            for (String propName : query.keySet()){
                Pair<Query.FilterOperator, Object> filterAndValue = query.get(propName);
                Query.Filter filter = new Query.FilterPredicate(propName,
                        filterAndValue.getFirst(), filterAndValue.getSecond());
                Query.Filter prevFilter = q.getFilter();
                q = new Query(kind).setFilter(prevFilter).setFilter(filter);
                q = q.setKeysOnly();
            }
            PreparedQuery pq = _ds.prepare(q);
            int c = pq.countEntities(FetchOptions.Builder.withDefaults());
            if (c != 0)
                contains = true;
            tx.commit();
        } catch (Exception e) {
            tx.rollback();
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
        }
        return contains;
    }


    /**
     * Builds a query filter from the given <code>Entity</code> property names and values and add sorting from
     * <code>Map</code> sorts.
     *
     * <br>
     * <code>
     *  QueryStore q = new QueryStore(kind).filter(f1).filter(f2).filter(fn).addSort(s).addSort(sn); // and so forth
     * </code>
     *
     * FIXME - Fix query object filter/sort getting wiped out
     *
     * @param sorts {@code Map} of sort direction
     * @return
     */
    protected Iterator<Entity> querySortedEntitiesLike(
            String kind,
            Map<String, Pair<Query.FilterOperator, Object>> query, Map<String, Query.SortDirection> sorts){

        Preconditions.checkNotNull(query, "QueryStore object can't be null");
        Preconditions.checkNotNull(sorts, "Sort can't be null");
        LOG.debug("QueryStore map="+query.toString());

        PreparedQuery pq = null;

        // Sort
        Iterator<Map.Entry<String, Query.SortDirection>> sortIterator = sorts.entrySet().iterator();
        Query q = new Query(kind);
        List<Query.Filter> subFilters = new ArrayList<Query.Filter>();
        if (!query.isEmpty()){
            // Apply filters and sorting for fields given in the filter query
            for (String propName : query.keySet()){
                LOG.debug("Filter Property name="+propName);
                Pair<Query.FilterOperator, Object> filterAndValue = query.get(propName);
                Query.FilterOperator operator = filterAndValue.getFirst();
                Object value = filterAndValue.getSecond();
                Query.Filter filter = null;
                if (propName.equals("_id")){
                    filter = new Query.FilterPredicate(Entity.KEY_RESERVED_PROPERTY, operator,
                            KeyStructure.createKey(kind, String.valueOf(value)));
                } else {
                    filter = new Query.FilterPredicate(propName, operator, value);
                }
                Query.Filter prevFilter = q.getFilter();
                if (sorts.get(propName) != null){
                    q = new Query(kind).setFilter(prevFilter).setFilter(filter)
                            .addSort(propName, sorts.get(propName));
                    sorts.remove(propName); // remove it
                } else {
                    q = new Query(kind).setFilter(prevFilter).setFilter(filter);
                }
                subFilters.add(filter);
            }
        } else if (query == null || query.isEmpty()){
            while(sortIterator.hasNext()){
//				Map.Entry<String, SortDirection> sort = sortIterator.next();
//				SortPredicate prevSort = q.getSortPredicates().get(q.getSortPredicates().size()-1); // Unsafe
//				if (prevSort != null){
//					q = new QueryStore(_collName).addSort(prevSort.getPropertyName(), prevSort.getDirection())
//							.addSort(sort.getKey(), sort.getValue());
//				}
                Map.Entry<String, Query.SortDirection> sort = sortIterator.next();
                q = new Query(kind).addSort(sort.getKey(), sort.getValue());
            }
        }
        pq = _ds.prepare(q);
        Iterator<Entity> res = pq.asIterator();
        //List asList = Lists.newArrayList(res);
        return res;
    }

    protected <T> List<T> copyIterator(Iterator<T> it){
        List<T> copy = new ArrayList<T>();
        while (it.hasNext()){
            copy.add(it.next());
        }
        return copy;
    }


    /**
     * QueryStore all entities on a given collection
     *
     * @return
     */
    protected Iterator<Entity> queryEntities(String kind){
        Query q = new Query(kind);
        PreparedQuery pq = _ds.prepare(q);
        return pq.asIterator();
    }

    protected Iterator<Entity> querySortedEntities(String kind, Map<String,Query.SortDirection> sorts){
        PreparedQuery pq = null;
        Iterator<Map.Entry<String, Query.SortDirection>> it = sorts.entrySet().iterator();
        Query q = new Query(kind);
        while(it.hasNext()){
            Map.Entry<String, Query.SortDirection> entry = it.next();
            q = new Query(kind)
                    .addSort(entry.getKey(), entry.getValue());

        }
        pq = _ds.prepare(q);
        return pq.asIterator();
    }

    /**
     * Get a list of entities that matches the properties of a given
     * <code>Entity</code>.
     * This does not include the id.
     *
     * @return
     */
    protected Iterator<Entity> queryEntitiesLike(String kind, Map<String,
            Pair<Query.FilterOperator, Object>> queryParam){
        Preconditions.checkNotNull(queryParam, "Null query object");
        Map<String,Pair<Query.FilterOperator, Object>> query = validateQuery(queryParam);
        Query q = new Query(kind);
        for (String propName : query.keySet()){
            Pair<Query.FilterOperator, Object> filterAndValue = query.get(propName);
            Query.Filter filter = new Query.FilterPredicate(propName,
                    filterAndValue.getFirst(), filterAndValue.getSecond());
            Query.Filter prevFilter = q.getFilter();
            q = new Query(kind).setFilter(prevFilter).setFilter(filter);
        }
        PreparedQuery pq = _ds.prepare(q);
        return pq.asIterator();
    }

    /**
     * Validates a query before it gets passed into the GAE api.
     * Replace and/or transform an object into GAE Datastore supported type
     *
     * @param query pairs of {@code Query.FilterOperator} operators
     * @return the validated query object
     */
    protected Map<String, Pair<Query.FilterOperator, Object>> validateQuery(
            Map<String, Pair<Query.FilterOperator, Object>> query) {
        //LOG.debug("QueryStore object type=" + query.getSecond().getClass().getName());
        Map<String,Object> toReplace = new HashMap<String,Object>();
        Set<Map.Entry<String, Pair<Query.FilterOperator, Object>>> entrySet = query.entrySet();
        for (Map.Entry<String, Pair<Query.FilterOperator, Object>> entry : entrySet){
            String field = entry.getKey();
            Pair<Query.FilterOperator, Object> value = entry.getValue();
//			if (valueType.getSecond() instanceof ObjectId){
//				Pair<FilterOperator, Object> newValue
//					= new Pair<QueryStore.FilterOperator, Object>(valueType.getFirst(),
//							((ObjectId)valueType.getSecond()).toStringMongod());
//				toReplace.put(field, newValue);
//			} else if (!GAE_SUPPORTED_TYPES.contains(valueType.getSecond().getClass())) {
//				throw new IllegalArgumentException("Unsupported filter compare valueType: " + valueType.getSecond().getClass());
//			}

            if (!GAE_SUPPORTED_TYPES.contains(value.getSecond().getClass())) {
                throw new IllegalArgumentException("Unsupported filter compare valueType: " + value.getSecond().getClass());
            }

        }

        Iterator<String> it = toReplace.keySet().iterator();
        while(it.hasNext()){
            String keyToReplace = it.next();
            query.put(keyToReplace, (Pair<Query.FilterOperator, Object>) toReplace.get(keyToReplace));
        }

        return query;
    }
}
