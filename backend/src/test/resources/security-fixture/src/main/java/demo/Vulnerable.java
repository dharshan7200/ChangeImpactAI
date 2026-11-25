package demo;

import java.io.ObjectInputStream;
import java.security.MessageDigest;
import java.util.Random;
import javax.crypto.Cipher;
import javax.persistence.EntityManager;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;

public class Vulnerable {
    private static final String API_KEY = "sk-live-1234567890";
    private EntityManager em;
    private Logger log;

    public Object find(String name) {
        return em.createQuery("select u from User u where u.name = '" + name + "'").getResultList();
    }
    public Object safe(String name) {
        return em.createQuery("select u from User u where u.name = :n").setParameter("n", name).getResultList();
    }
    public byte[] hash(byte[] in) throws Exception { return MessageDigest.getInstance("MD5").digest(in); }
    public Cipher cipher() throws Exception { return Cipher.getInstance("AES"); }
    public Cipher strong() throws Exception { return Cipher.getInstance("AES/GCM/NoPadding"); }
    public void run(String cmd) throws Exception { Runtime.getRuntime().exec(cmd); }
    public Object read(ObjectInputStream ois) throws Exception { return ois.readObject(); }
    public Object xml() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        return f.newDocumentBuilder();
    }
    public Object xmlSafe() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder();
    }
    public String sessionToken() { return Long.toHexString(new Random().nextLong()); }
    public void login(String user, String password) { log.info("login {} {}", user, password); }
}
