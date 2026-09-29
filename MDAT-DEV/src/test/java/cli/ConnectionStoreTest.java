package cli;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * task 库白盒：隔离/WAL/CRUD/符号链接拒判。
 */
public class ConnectionStoreTest {

    private File tmpDir() throws Exception {
        return Files.createTempDirectory("mdut-store-test").toFile();
    }

    @Test
    public void crudAndIsolation() throws Exception {
        File root = tmpDir();
        ConnectionStore a = new ConnectionStore(root.getAbsolutePath(), "taskA");
        ConnectionStore b = new ConnectionStore(root.getAbsolutePath(), "taskB");

        Map<String, String> rec = new LinkedHashMap<>();
        rec.put("databasetype", "mysql");
        rec.put("ipaddress", "10.0.0.1");
        rec.put("port", "3306");
        rec.put("username", "root");
        rec.put("password", "pw");
        rec.put("database", "mysql");
        rec.put("timeout", "5");
        long idA = a.addConnection(rec);

        // 隔离：taskB 看不到 taskA 的连接
        assertNull(b.findById(String.valueOf(idA)));
        assertEquals(0, b.listConnections(null).length());
        assertNotNull(a.findById(String.valueOf(idA)));
        assertEquals("mysql", a.findById(String.valueOf(idA)).get("databasetype"));

        assertEquals(1, a.deleteById(String.valueOf(idA)));
        assertNull(a.findById(String.valueOf(idA)));
        a.close();
        b.close();
    }

    @Test
    public void walModeEnabled() throws Exception {
        File root = tmpDir();
        ConnectionStore s = new ConnectionStore(root.getAbsolutePath(), "waltask");
        // WAL 开启会生成 -wal 伴生文件（检查点前）
        s.addConnection(newTcRec());
        File db = new File(s.getTaskDir(), "data.db");
        File wal = new File(s.getTaskDir(), "data.db-wal");
        assertTrue("WAL 伴生文件应存在", wal.exists() || new File(s.getTaskDir(), "data.db-shm").exists());
        assertEquals("ok", s.quickCheck());
        s.close();
        assertFalse(db.isDirectory());
    }

    @Test
    public void symlinkRefused() throws Exception {
        File root = tmpDir();
        ConnectionStore s = new ConnectionStore(root.getAbsolutePath(), "symtask");
        s.addConnection(newTcRec());
        s.close();

        File real = new File(root, "real.db");
        Files.copy(new File(root, "symtask/data.db").toPath(), real.toPath());
        File taskDir = new File(root, "symtask2");
        taskDir.mkdirs();
        Files.createSymbolicLink(new File(taskDir, "data.db").toPath(), real.toPath());
        try {
            new ConnectionStore(root.getAbsolutePath(), "symtask2");
            fail("符号链接 data.db 应拒判");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void invalidTaskNameRefused() throws Exception {
        File root = tmpDir();
        try {
            new ConnectionStore(root.getAbsolutePath(), "../evil");
            fail("非法 task 名应拒判");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertTrue(ConnectionStore.validTaskName("eng-1_x"));
        assertFalse(ConnectionStore.validTaskName("../evil"));
        assertFalse(ConnectionStore.validTaskName(""));
    }

    @Test
    public void groupCrud() throws Exception {
        File root = tmpDir();
        ConnectionStore s = new ConnectionStore(root.getAbsolutePath(), "grouptask");
        s.addGroup("web");
        s.addGroup("web"); // 幂等
        assertEquals(1, s.deleteGroup("web"));
        assertEquals(0, s.deleteGroup("web"));
        s.close();
    }

    private Map<String, String> newTcRec() {
        Map<String, String> rec = new LinkedHashMap<>();
        rec.put("databasetype", "redis");
        rec.put("ipaddress", "127.0.0.1");
        rec.put("port", "6379");
        rec.put("timeout", "5");
        return rec;
    }
}
