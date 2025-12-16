package fr.cgi.learninghub.swarm.resource;

public record WordpressInstallerSpec(SiteSpec site, DatabaseSpec database) {
    public record SiteSpec(String id, String host, String name, String path) {}
    public record DatabaseSpec (String host, int port, String name, String user, String passwordSecretName, String passwordSecretKey) {
        public DatabaseSpec(String host, String name, String user, String passwordSecretName, String passwordSecretKey) {
            this(host, 3306, name, user, passwordSecretName, passwordSecretKey);
        }
    }
}