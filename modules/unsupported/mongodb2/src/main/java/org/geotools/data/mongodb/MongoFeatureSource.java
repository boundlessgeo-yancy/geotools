package org.geotools.data.mongodb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geotools.data.FeatureReader;
import org.geotools.data.Query;
import org.geotools.data.simple.FilteringSimpleFeatureReader;
import org.geotools.data.simple.SimpleFeatureReader;
import org.geotools.data.store.ContentEntry;
import org.geotools.data.store.ContentFeatureSource;
import org.geotools.filter.visitor.PostPreProcessFilterSplittingVisitor;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.util.logging.Logging;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.opengis.filter.sort.SortBy;
import org.opengis.filter.sort.SortOrder;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;

public class MongoFeatureSource extends ContentFeatureSource {

    static Logger LOG = Logging.getLogger("org.geotools.data.mongodb");

    DBCollection collection;
    CollectionMapper  mapper;

    public MongoFeatureSource(ContentEntry entry, Query query) {
        super(entry, query);
        collection = getDataStore().getDb().getCollection(entry.getTypeName());
        initMapper();
    }

    void initMapper() {
        CollectionMapper mapper = getDataStore().getDefaultMapper();

        //snif the first object to determine what type it is
        if (collection.count() > 0) {
            DBObject obj = collection.findOne();
            if (obj.containsField("geometry") && obj.containsField("properties")) {
                mapper = new GeoJSONMapper();
            }
            else {
                //add hoc, try to figure out from values if one is the geometry
                List<String> candidates = new ArrayList();
                for (String key : obj.keySet()) {
                    Object val = obj.get(key);
                    if (val instanceof List) {
                        candidates.add(key);
                    }
                }
                if (candidates.size() == 1) {
                    mapper = new AddHocMapper(candidates.get(0));
                }
            }
        }
        setMapper(mapper);
    }

    public DBCollection getCollection() {
        return collection;
    }

    public CollectionMapper getMapper() {
        return mapper;
    }

    public void setMapper(CollectionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected SimpleFeatureType buildFeatureType() throws IOException {
       return mapper.buildFeatureType(entry.getName(), collection);
    }

    @Override
    public MongoDataStore getDataStore() {
        return (MongoDataStore) super.getDataStore();
    }

    @Override
    protected ReferencedEnvelope getBoundsInternal(Query query) throws IOException {
        //TODO: crs?
        FeatureReader r = getReader(query);
        try {
            ReferencedEnvelope e = new ReferencedEnvelope();
            if (r.hasNext()) {
                e.init(r.next().getBounds());
            }
            while(r.hasNext()) {
                e.include(r.next().getBounds());
            }
            return e;
        }
        finally {
            r.close();
        }
    }

    @Override
    protected int getCountInternal(Query query) throws IOException {
        Filter f = query.getFilter();
        if (isAll(f)) {
            LOG.fine("count(all)");
            return (int) collection.count();
        }

        Filter[] split = splitFilter(f);
        if (!isAll(split[1])) {
            return -1;
        }

        DBObject q = toQuery(f);
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("count(" + q + ")");
        }
        return (int) collection.count(q);
    }

    @Override
    protected FeatureReader<SimpleFeatureType, SimpleFeature> getReaderInternal(Query query) 
            throws IOException {

        List<Filter> postFilter = new ArrayList();
        DBCursor cursor = toCursor(query, postFilter);
        SimpleFeatureReader r = new MongoFeatureReader(cursor, this);

        if (!postFilter.isEmpty()) {
            r = new FilteringSimpleFeatureReader(r, postFilter.get(0));
        }
        return r;
    }

    @Override
    protected boolean canOffset() {
        return true;
    }

    @Override
    protected boolean canLimit() {
        return true;
    }

    @Override
    protected boolean canRetype() {
        return true;
    }

    @Override
    protected boolean canSort() {
        return true;
    }

    @Override
    protected boolean canFilter() {
        return true;
    }

    DBCursor toCursor(Query q, List<Filter> postFilter) {
        DBObject query = new BasicDBObject();

        Filter f = q.getFilter();
        if (!isAll(f)) {
            Filter[] split = splitFilter(f);
            query = toQuery(split[0]);
            if (!isAll(split[1])) {
                postFilter.add(split[1]);
            }
        }

        DBCursor c = null;
        if (q.getPropertyNames() != Query.ALL_NAMES) {
            BasicDBObject keys = new BasicDBObject();
            for (String p : q.getPropertyNames()) {
                keys.put(mapper.getPropertyPath(p), 1);
            }
            if (!keys.containsField(mapper.getGeometryPath())) {
                keys.put(mapper.getGeometryPath(), 1);
            }
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(String.format("find(query=%s, keys=%s)", query, keys));
            }
            c = collection.find(query, keys);
        }
        else {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(String.format("find(query=%s)", query));
            }
            c = collection.find(query);
        }

        if (q.getStartIndex() != null && q.getStartIndex() != 0) {
            c = c.skip(q.getStartIndex());
        }
        if (q.getMaxFeatures() != Integer.MAX_VALUE) {
            c = c.limit(q.getMaxFeatures());
        }

        if (q.getSortBy() != null) {
            BasicDBObject orderBy = new BasicDBObject();
            for (SortBy sortBy : q.getSortBy()) {
                String propName = sortBy.getPropertyName().getPropertyName();
                orderBy.append(propName, sortBy.getSortOrder() == SortOrder.ASCENDING ? 1 : -1);
            }
            c = c.sort(orderBy);
        }

        return c;
    }

    DBObject toQuery(Filter f) {
        if (isAll(f)) {
            return new BasicDBObject(); 
        }

        return (DBObject) f.accept(new FilterToMongo(mapper), null); 
    }

    boolean isAll(Filter f) {
        return f == null || f == Filter.INCLUDE;
    }

    Filter[] splitFilter(Filter f) {
        PostPreProcessFilterSplittingVisitor splitter = 
            new PostPreProcessFilterSplittingVisitor(getDataStore().getFilterCapabilities(), null, null);
        f.accept(splitter, null);
        return new Filter[]{splitter.getFilterPre(), splitter.getFilterPost()};
    }

}
