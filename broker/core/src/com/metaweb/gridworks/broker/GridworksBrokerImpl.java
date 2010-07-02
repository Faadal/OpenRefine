package com.metaweb.gridworks.broker;

import static com.sleepycat.persist.model.Relationship.MANY_TO_ONE;

import java.io.File;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.persist.EntityCursor;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;
import com.sleepycat.persist.SecondaryIndex;
import com.sleepycat.persist.StoreConfig;
import com.sleepycat.persist.model.Entity;
import com.sleepycat.persist.model.PrimaryKey;
import com.sleepycat.persist.model.SecondaryKey;

public class GridworksBrokerImpl extends GridworksBroker {
                
    protected static final Logger logger = LoggerFactory.getLogger("gridworks.broker.local");
    
    Environment env;
    
    EntityStore projectStore;
    EntityStore lockStore;
    EntityStore userStore;
    
    PrimaryIndex<String,Project> projectById;
    PrimaryIndex<String,Lock> lockById;

    SecondaryIndex<String,String,Lock> locksByProject;
    
    Timer timer;
    Expirer expirer;

    @Override
    public void init(ServletConfig config) throws Exception {
        logger.trace("> init");
        super.init(config);

        timer = new Timer();
        expirer = new Expirer();
        timer.schedule(expirer, LOCK_EXPIRATION_CHECK_DELAY, LOCK_EXPIRATION_CHECK_DELAY);
        
        String dataDir = config.getInitParameter("gridworks.data");
        if (dataDir == null) dataDir = "data";
        File dataPath = new File(dataDir);
        if (!dataPath.exists()) dataPath.mkdirs();
        
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);
        env = new Environment(dataPath, envConfig); 

        StoreConfig storeConfig = new StoreConfig();
        storeConfig.setAllowCreate(true);
        storeConfig.setTransactional(true);
        projectStore = new EntityStore(env, "ProjectsStore", storeConfig);
        lockStore = new EntityStore(env, "LockStore", storeConfig);
        
        projectById = projectStore.getPrimaryIndex(String.class, Project.class);
        lockById = lockStore.getPrimaryIndex(String.class, Lock.class);
        
        locksByProject = lockStore.getSecondaryIndex(lockById, String.class, "pid"); 
        logger.trace("< init");
    }
    
    @Override
    public void destroy() throws Exception {
        logger.trace("> destroy");
        super.destroy();
        
        if (projectStore != null) {
            projectStore.close();
            projectById = null;            
        } 

        if (lockStore != null) {
            lockStore.close();
            lockById = null;
        } 
        
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
        
        if (env != null) {
            env.close();
            env = null;
        }
        logger.trace("< destroy");
    }

    class Expirer extends TimerTask {
        public void run() {
            if (lockById != null) {
                logger.trace("> expire");
                Transaction txn = env.beginTransaction(null, null);
                try {
                    EntityCursor<Lock> cursor = lockById.entities();
                    try {
                        for (Lock lock : cursor) {
                            if (lock.timestamp + LOCK_DURATION < System.currentTimeMillis()) {
                                logger.trace("Found expired lock {}", lock.id);
                                try {
                                    releaseLock(null, lock.pid, lock.uid, lock.id);
                                } catch (Exception e) {
                                    logger.error("Exception while expiring lock for project '" + lock.pid + "'", e);
                                }
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                } finally {
                    if (txn != null) {
                        txn.abort();
                        txn = null;
                    }
                }
                logger.trace("< expire");
            }
        }
    }
    
    // ---------------------------------------------------------------------------------

    @Override
    protected HttpClient getHttpClient() {
        return new DefaultHttpClient();
    }
    
    // ---------------------------------------------------------------------------------
    
    @Override
    protected void expire(HttpServletResponse response) throws Exception {
        expirer.run();
        respond(response, OK);
    }
        
    @Override
    protected void obtainLock(HttpServletResponse response, String pid, String uid, int locktype, String lockvalue) throws Exception {

        Lock lock = null;

        Transaction txn = env.beginTransaction(null, null);

        try {

            EntityCursor<Lock> cursor = locksByProject.subIndex(pid).entities(); 
            
            try {
                for (Lock l : cursor) {
                    if (locktype == ALL) {
                        if (l.type == ALL) {
                            lock = l;
                            break;
                        }
                    } else if (locktype == COLUMN) {
                        if (l.type == ALL || 
                           (l.type == COLUMN && l.value.equals(lockvalue))) {
                            lock = l;
                            break;
                        }
                    } else if (locktype == CELL) {
                        if (l.type == ALL || 
                           (l.type == COLUMN && l.value.equals(lockvalue.split(",")[0])) || 
                           (l.type == CELL && l.value.equals(lockvalue))) {
                            lock = l;
                            break;
                        }
                    }
                }
            } finally {
                cursor.close(); 
            } 
    
            if (lock == null) {
                lock = new Lock(Long.toHexString(txn.getId()), pid, uid, locktype, lockvalue);
                lockById.put(txn, lock);
                txn.commit();
            }

        } finally {
            if (txn != null) {
                txn.abort();
                txn = null;
            }
        }
        
        respond(response, lockToJSON(lock));
    }
    
    @Override
    protected void releaseLock(HttpServletResponse response, String pid, String uid, String lid) throws Exception {

        Transaction txn = env.beginTransaction(null, null);

        try {
            Lock lock = getLock(lid, pid, uid);
            if (lock != null) {
                if (!lock.uid.equals(uid)) {
                    throw new RuntimeException("User id doesn't match the lock owner, can't release the lock");
                }
                lockById.delete(pid);
                txn.commit();
            }
        } finally {
            if (txn != null) {
                txn.abort();
                txn = null;
            }
        }
                
        if (response != null) { // this because the expiration thread can call this method without a real response
            respond(response, OK);
        }
    }
    
    // ----------------------------------------------------------------------------------------------------

    @Override
    protected void startProject(HttpServletResponse response, String pid, String uid, String lid, byte[] data, String metadata, int rev) throws Exception {
        
        Transaction txn = env.beginTransaction(null, null);

        try {
            if (projectById.contains(pid)) {
                throw new RuntimeException("Project '" + pid + "' already exists");
            }
            
            Lock lock = getLock(lid, pid, uid);
            
            if (lock.type != ALL) {
                throw new RuntimeException("The lock you have is not enough to start a project");
            }
            
            projectById.put(txn, new Project(pid, data, metadata, rev));
            txn.commit();
        } finally {
            if (txn != null) {
                txn.abort();
                txn = null;
            }
        }
        
        respond(response, OK);
    }
    
    @Override
    protected void addTransformations(HttpServletResponse response, String pid, String uid, String lid, List<String> transformations) throws Exception {

        Transaction txn = env.beginTransaction(null, null);
        
        try {
            Project project = getProject(pid);
            
            if (project == null) {
                throw new RuntimeException("Project '" + pid + "' not found");
            }

            Lock lock = getLock(lid, pid, uid);

            if (lock.type == ALL) {
                project.transformations.addAll(transformations);
            } else {
                for (String s : transformations) {
                    JSONObject o = new JSONObject(s);
                    
                    int type = o.getInt("op_type");
                    String value = o.getString("op_value");
                    if (lock.type == COLUMN) {
                        if (type == COLUMN) {
                            if (value != null && value.equals(lock.value)) {
                               project.transformations.add(s);
                            } else {
                                throw new RuntimeException("Can't apply '" + s + "': you have a lock for column '" + lock.value + "' and you're attempting to modify column '" + value + "'.");
                            }
                        } else if (type == CELL) {
                            String column = value.split(",")[0];
                            if (column != null && column.equals(lock.value)) {
                                project.transformations.add(s);
                            } else {
                                throw new RuntimeException("Can't apply '" + s + "': you have a lock for column '" + lock.value + "' and you're attempting to modify cell '" + value + "' in another column.");
                            }
                        }
                    } else if (lock.type == CELL) {
                        if (type == COLUMN) {
                            throw new RuntimeException("Can't apply '" + s + "': you offered a lock for a single cell and you're attempting an operation for the entire column.");
                        } else if (type == CELL) {
                            if (value != null && value.equals(lock.value)) {
                                project.transformations.add(s);
                            } else {
                                throw new RuntimeException("Can't apply '" + s + "': you have a lock for cell '" + lock.value + "' and you're attempting to modify cell '" + value + "'.");
                            }
                        }
                    }
                }
            }

            txn.commit();
        } finally {
            if (txn != null) {
                txn.abort();
                txn = null;
            }
        }
        
        respond(response, OK);
    }

    // ---------------------------------------------------------------------------------
    
    @Override
    protected void openProject(HttpServletResponse response, String pid) throws Exception {
        Project project = getProject(pid);

        Writer w = response.getWriter();
        JSONWriter writer = new JSONWriter(w);
        writer.object();
            writer.key("data"); writer.value(project.data);
            writer.key("metadata"); writer.value(new JSONObject(project.metadata));
            writer.key("transformations"); 
            writer.array();
                for (String s : project.transformations) {
                    writer.value(new JSONObject(s));
                }
            writer.endArray();
        writer.endObject();
        w.flush();
        w.close();
    }
        
    // ---------------------------------------------------------------------------------

    @Override
    protected void getState(HttpServletResponse response, String pid, String uid, int rev) throws Exception {

        Project project = getProject(pid);
        
        Writer w = response.getWriter();
        JSONWriter writer = new JSONWriter(w);
        
        writer.object();
        writer.key("transformations"); 
        writer.array();
            int size = project.transformations.size();
            for (int i = rev; i < size; i++) {
                writer.value(project.transformations.get(i));
            }
        writer.endArray();
        writer.endObject();

        EntityCursor<Lock> cursor = locksByProject.subIndex(pid).entities(); 
        
        try {
            writer.object();
            writer.key("locks"); 
            writer.array();
            for (Lock lock : cursor) {
                writer.value(lockToJSON(lock));
            }
            writer.endArray();
            writer.endObject();
            
            w.flush();
            w.close();
        } finally {
            cursor.close(); 
        } 
    }
    
    // ---------------------------------------------------------------------------------

    Project getProject(String pid) {
        Project project = projectById.get(pid);
        if (project == null) {
            throw new RuntimeException("Project '" + pid + "' could not be found: are you sure is not managed by another broker?");
        }
        return project;
    }
        
    @Entity
    static class Project {
        
        @PrimaryKey
        String pid;

        List<String> transformations = new ArrayList<String>(); 
        
        byte[] data;

        String metadata;
        
        int rev;

        Project(String pid, byte[] data, String metadata, int rev) {
            this.pid = pid;
            this.data = data;
            this.metadata = metadata;
            this.rev = rev;
        }
        
        @SuppressWarnings("unused")
        private Project() {}
    }
    
    // ---------------------------------------------------------------------------------
    
    Lock getLock(String lid, String pid, String uid) {
        Lock lock = lockById.get(lid);
        checkLock(lock, lid, pid, uid);
        return lock;
    }

    void checkLock(Lock lock, String lid, String pid, String uid) {
        if (lock == null) {
            throw new RuntimeException("No lock was found with the given Lock id '" + lid + "', you have to have a valid lock on a project in order to start it");
        }
        
        if (!lock.pid.equals(pid)) {
            throw new RuntimeException("Lock '" + lock.id + "' is for another project: " + lock.pid);
        }
        
        if (!lock.uid.equals(uid)) {
            throw new RuntimeException("Lock '" + lock.id + "' is owned by another user: " + lock.uid);
        }
    }

    Lock getLock(String pid, String uid, int locktype) {
        Lock lock = null;
        EntityCursor<Lock> cursor = locksByProject.subIndex(pid).entities(); 
        
        try {
            for (Lock l : cursor) {
                if (uid.equals(l.uid) && (locktype == l.type)) {
                    lock = l;
                    break;
                }
            }
        } finally {
            cursor.close(); 
        } 
        
        return lock;
    }    
    
    JSONObject lockToJSON(Lock lock) throws JSONException {
        JSONObject o = new JSONObject();
        if (lock != null) {
            o.put("lock_id", lock.id);
            o.put("project_id", lock.pid);
            o.put("user_id", lock.uid);
            o.put("timestamp", lock.timestamp);
        }
        return o;
    }
    
    @Entity
    static class Lock {

        @PrimaryKey
        String id;
        
        @SecondaryKey(relate=MANY_TO_ONE)
        String pid;
        
        String uid;
        
        int type;
        
        String value;
        
        long timestamp;
        
        Lock(String id, String pid, String uid, int type, String value) {
            this.id = id;
            this.pid = pid;
            this.uid = uid;
            this.type = type;
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }
                
        @SuppressWarnings("unused")
        private Lock() {}
    }
}