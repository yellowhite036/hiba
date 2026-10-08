package org.gslab.fileProtection.config;

/**
 * Database configuration settings
 */
public class DatabaseConfig {

    private String url;
    private String username;
    private String password;

    public DatabaseConfig() {
        // Default SQLite configuration
        this.url = "jdbc:sqlite:blockchain_manager.db";
        this.username = "user";
        this.password = "password";
    }

    public DatabaseConfig(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return "DatabaseConfig{" + "url='" + url + '\'' + ", username='" + username + '\'' + ", password='***'" + '}';
    }
}
