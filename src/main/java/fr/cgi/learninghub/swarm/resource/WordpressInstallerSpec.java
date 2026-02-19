package fr.cgi.learninghub.swarm.resource;

import java.util.List;

public record WordpressInstallerSpec(SiteSpec site, DatabaseSpec database, StorageSpec storage) {
    public record SiteSpec(String id, String host, String name, String path, String adminEmail, String adminPassword) {}
    public record DatabaseSpec (String host, int port, String name, String user, String passwordSecretName, String passwordSecretKey) {
        public DatabaseSpec(String host, String name, String user, String passwordSecretName, String passwordSecretKey) {
            this(host, 3306, name, user, passwordSecretName, passwordSecretKey);
        }
    }

    public record StorageSpec(String size, String storageClassName, List<String> accessModes) {}
}