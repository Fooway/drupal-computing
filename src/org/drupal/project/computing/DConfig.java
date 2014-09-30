package org.drupal.project.computing;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;

import java.io.*;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.Assert.*;


/**
 * This is the config class to help initialize DSite and DApplication.
 * Note that there's no file that maps to it. This is purely internal.
 */
public class DConfig {

    protected Logger logger = DUtils.getInstance().getPackageLogger();

    private static DConfig defaultConfig;

    /**
     * This is the main place we save configurations.
     * We don't use hard coded fields because we don't know which are needed.
     */
    protected Properties properties;

    public DConfig() {
        properties = new Properties();
        // properties = System.getProperties();
        // here we can initialize a few default properties too.
        //properties.setProperty("defaultAutoCommit", "false");
    }

    /**
     * Default constructor that construct the config object.
     *
     * @param properties
     */
    public DConfig(Properties properties) {
        this();
        this.properties.putAll(properties);
    }


    /**
     * Factory method. Load default config from config.properties file.
     * Load priority:
     * 1. Arbitrary properties file specified by java -Ddrupal.computing.config.file
     * 2. Properties file in current working directory
     * 3. Properties file in the same directory as the Jar file
     * 4. Properties file in "user.home".
     *
     * @return A DConfig object with loaded properties.
     */
    public static DConfig loadDefault(boolean reload) {
        // load config file only when needed.
        if (reload || defaultConfig == null) {
            DConfig config = new DConfig();
            String configFileName = config.getProperty("drupal.computing.config.file", "config.properties");
            Reader configFileReader = null;

            // try to load config file.
            if (configFileName.equals("config.properties")) {
                try {
                    File configFile = DUtils.getInstance().locateFile(configFileName);
                    assert configFile.exists();
                    configFileReader = new FileReader(configFile);
                } catch (FileNotFoundException e) {
                    config.logger.warning("Cannot find configuration file: " + configFileName);
                }
            } else {
                try {
                    configFileReader = new FileReader(configFileName);
                } catch (FileNotFoundException e) {
                    config.logger.warning("Cannot find configuration file: " + configFileName);
                }
            }

            // read config file and load into config.
            if (configFileReader != null) {
                Properties configProperties = new Properties();
                try {
                    configProperties.load(configFileReader);
                } catch (IOException e) {
                    config.logger.warning("Cannot read configuration file: " + configFileName);
                }
                config.properties.putAll(configProperties);
            }

            // set defaultConfig
            defaultConfig = config;
        }
        return defaultConfig;
    }

    public static DConfig loadDefault() {
        return loadDefault(false);
    }

    /**
     * Load priority:
     * 1. Property set in this.properties.
     * 2. Java system property
     * 3. Property set in system ENV: replace '.' with '_' and lower case to upper case.
     *
     * @param propertyName
     * @param defaultValue
     * @return propertyValue if set, of defaultValue
     */
    public String getProperty(String propertyName, String defaultValue) {
        assert StringUtils.isNotBlank(propertyName);

        // 1. Property set in this.properties.
        if (properties.containsKey(propertyName)) {
            return properties.getProperty(propertyName);
        }

        // 2. Java system property (not assuming it's already in system properties)
        Properties systemProperties = System.getProperties();
        if (systemProperties.containsKey(propertyName)) {
            return systemProperties.getProperty(propertyName);
        }

        // 3.Property set in system ENV: replace '.' with '_' and lower case to upper case.
        String envPropertyName = propertyName.replaceAll("\\.", "_").toUpperCase();
        String envValue = System.getenv(envPropertyName);

        return envValue == null ? defaultValue : envValue;
    }

    public void setProperty(String propertyName, String value) {
        assert StringUtils.isNotBlank(propertyName);
        properties.setProperty(propertyName, value);
    }



    /**
     * Returns the database configuration to initialize Drupal database configuration.
     *
     * @see <a href="http://commons.apache.org/dbcp/configuration.html">DBCP configurations</a>
     * @see <a href="http://dev.mysql.com/doc/refman/5.1/en/connector-j-reference-configuration-properties.html">MySQL configurations</a>
     *
     * @param db The database setting to retrieve if using Drush or Settings File.
     *           For example, db=='test' will retrieve $database['test'] database settings.
     *           For more advanced database settings, it is suggested to use -Ddrupal.db.* directly.
     * @return Properties for DBCP BasicDataSourceFactory.createDataSource()
     */
    public Properties getDbProperties(String db) throws DConfigException {
        Properties dbProperties;
        // try to read from -D properties.
        try {
            // this doesn't use the "db" identifier.
            dbProperties = getDbPropertiesDirectly();
            logger.info("Using database config directly.");
            return dbProperties;
        } catch (DConfigException e) {
            //logger.fine(e.getMessage()); // and then try the next approach.
        }

        // try to use drush
        try {
            dbProperties = getDbPropertiesFromDrush(db);
            logger.info("Using database config from drush.");
            return dbProperties;
        } catch (DConfigException e) {
            // logger.fine(e.getMessage()); // and then try the next approach.
        }

        // try to read from settings.php.
        try {
            dbProperties = getDbPropertiesFromSettings(db);
            logger.info("Using database config from settings.php.");
            return dbProperties;
        } catch (DConfigException e) {
            //logger.fine(e.getMessage()); // and then try the next approach.
        }

        // nothing works, throw exception.
        throw new DConfigException("Cannot find Drupal database connection configuration.");
    }

    /**
     * @return Default database properties from $databases['default'] or $databases['computing'] if not set directly
     *  through -Ddrupal.db.*
     * @throws DConfigException
     */
    public Properties getDbProperties() throws DConfigException {
        return getDbProperties(null);
    }


    private Properties extractDbProperties(Properties properties) {
        Properties dbProperties = new Properties();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("drupal.db.")) {
                dbProperties.setProperty(name.substring("drupal.db.".length()), properties.getProperty(name));
            }
        }
        return dbProperties;
    }


    private Properties getDbPropertiesDirectly() throws DConfigException {
        Properties dbProperties = extractDbProperties(properties);
        if (dbProperties.containsKey("username") && dbProperties.containsKey("password")
                && dbProperties.containsKey("url") && dbProperties.containsKey("driverClassName")) {
            return dbProperties;
        } else {
            throw new DConfigException("Cannot get database configuration directly.");
        }
    }

    /**
     * Process database settings read from settings.php ($databases[] array) and save results directly in the properties.
     *
     * @param dbProperties Database properties read from settings.php.
     * @throws DConfigException
     */
    private void convertDbProperties(Properties dbProperties) throws DConfigException {
        // username/password should be in it already. only need to construct url and driverName.
        DDatabase.DatabaseDriver driver;
        try {
            driver = DDatabase.DatabaseDriver.valueOf(dbProperties.getProperty("driver").toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DConfigException("Database driver is not recognizable: " + dbProperties.getProperty("driver"));
        }

        String url = String.format("jdbc:%s://%s", driver.getJdbcName(), dbProperties.getProperty("host", "localhost"));
        if (StringUtils.isNotEmpty(dbProperties.getProperty("port"))) {
            url = url + ":" + dbProperties.getProperty("port");
        }
        url = url + "/" + dbProperties.getProperty("database", "");
        dbProperties.put("url", url);
        dbProperties.put("driverClassName", driver.getJdbcDriver());
    }

    /**
     * Returns the PHP that get database settings.
     * @param db
     * @return
     */
    private String getDbPhp(String db) {
        return StringUtils.isEmpty(db) ?
                "isset($databases['computing']) ? $databases['computing']['default'] : $databases['default']['default']" :
                "$databases['" + db + "']['default']";
    }

    private Properties getDbPropertiesFromDrush(String db) throws DConfigException {
        DUtils.Drush drush = new DUtils.Drush(this.getDrushExec());
        try {
            String dbPhp = "global $databases; return " + getDbPhp(db) + ";";
            String json = drush.computingEval(dbPhp);
            // FIXME: the settings could be nested php array, and can't map to Properties.class
            // however, if i use DUtils::fromJson(String) it will return a nested map which is not Properties.
            Properties dbProperties = DUtils.getInstance().getDefaultGson().fromJson(json, Properties.class);

            // process hostname if drush is remote-host and host==localhost. offer a override option.
            if (StringUtils.equalsIgnoreCase("localhost", dbProperties.getProperty("host"))
                    && properties.containsKey("drupal.db.host")) {
                dbProperties.setProperty("host", properties.getProperty("drupal.db.host"));
            }

            // process dbProperties from settings.php syntax to JDBC syntax
            convertDbProperties(dbProperties);

            // anything set in the properties will go in.
            dbProperties.putAll(extractDbProperties(properties));
            return dbProperties;
        } catch (DSiteException e) {
            //e.printStackTrace();
            throw new DConfigException("Cannot get database configuration from drush.");
        }
    }


    private Properties getDbPropertiesFromSettings(String db) throws DConfigException {
        File settingsFile = null;
        String settingsLocation = properties.getProperty("drupal.settings.file");
        if (StringUtils.isNotBlank(settingsLocation)) {
            settingsFile = new File(settingsLocation);
        } else {
            try {
                settingsFile = locateFile("settings.php");
            } catch (FileNotFoundException e) {}
        }

        if (settingsFile == null || !settingsFile.exists() || !settingsFile.isFile()) {
            throw new DConfigException("Cannot find settings.php file.");
        }

        // try to parse settings.php.
        try {
            DUtils.Php php = new DUtils.Php(this.getPhpExec());
            String databasesCode = php.extractVariable(settingsFile, "$databases");
            // FIXME: the settings could be nested php array, and can't map to Properties.class
            String phpCode = "<?php\n" + databasesCode + "\n" +
                    "echo json_encode(" + getDbPhp(db) + ");";
            String json = php.evaluate(phpCode);
            //System.out.println(json);
            Properties dbProperties = DUtils.getInstance().getDefaultGson().fromJson(json, Properties.class);

            // convert dbProperties to JDBC syntax.
            convertDbProperties(dbProperties);

            // anything set in the properties will go in.
            dbProperties.putAll(extractDbProperties(properties));
            return dbProperties;
        } catch (DSystemExecutionException e) {
            //e.printStackTrace();
            throw new DConfigException("Cannot parse settings file.");
        }
    }


    /**
     * Try to find a file in this order:
     * 1. the working directory,
     * 2. the same directory as the jar file located.
     * 3. drush "drupal_root"/"site_path"
     * 4. DRUPAL_ROOT/sites/default
     * 5. user home directory
     *
     * @param fileName File name to look for.
     * @return
     * @throws java.io.FileNotFoundException
     */
    public File locateFile(String fileName) throws FileNotFoundException {
        assert StringUtils.isNotBlank(fileName);
        File theFile = null;

        // try working directory.
        String workingDir = System.getProperty("user.dir");  // we don't allow override, so we don't use this.properties.
        theFile = new File(workingDir + File.separator + fileName);
        if (theFile.exists()) {
            return theFile;
        }

        // or else, try the same directory of the jar file
        String jarDir = DUtils.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        theFile = new File(jarDir + File.separator + fileName);
        if (theFile.exists()) {
            return theFile;
        }

        // or else, try drush site_path
        try {
            DUtils.Drush drush = new DUtils.Drush(this.getDrushExec());
            Properties coreStatus = drush.getCoreStatus();
            theFile = new File(coreStatus.getProperty("drupal_root", "") + File.separator
                    + coreStatus.getProperty("site_path", "") + File.separator + fileName);
            if (theFile.exists()) {
                return theFile;
            }
        } catch (DSiteException e) {}


        // or else, try DRUPAL_ROOT
        try {
            String drupalRoot = getDrupalRoot().getAbsolutePath();
            theFile = new File(drupalRoot + File.separator + "sites" + File.separator + "default"
                    + File.separator + fileName);
            if (theFile.exists()) {
                return theFile;
            }
        } catch (DConfigException e) {}

        // finally, try use home directory.
        String userDir = System.getProperty("user.home");
        theFile = new File(userDir + File.separator + fileName);
        if (theFile.exists()) {
            return theFile;
        }

        // if still can't find file, throw exception
        throw new FileNotFoundException("Cannot locate file: " + fileName);
    }

    /**
     * Return the Drupal database prefix string, or null if not valid.
     * @return Drupal database prefix string, or null if not valid.
     */
    @Deprecated
    public String getDbPrefix() {
        try {
            // first needs to get valid dbProperties, otherwise it doesn't make sense to return prefix alone.
            Properties dbProperties = getDbProperties();
            String dbPrefix = dbProperties.getProperty("prefix");
            if (StringUtils.isNotBlank(dbPrefix)) {
                return dbPrefix;
            } else {
                return null;
            }
        } catch (DConfigException e) {
            return null;
        }
    }


    /**
     * Get the max_batch_size from db configuration.
     * @return
     */
    @Deprecated
    public int getMaxBatchSize() {
        try {
            Properties dbProperties = getDbProperties();
            int maxBatchSize = Integer.parseInt(dbProperties.getProperty("max_batch_size"));
            return maxBatchSize;
        } catch (DConfigException e) {
            logger.fine("drupal.db.max_batch_size is not set. Use default 0.");
            return 0;
        } catch (Exception e) {
            logger.warning("Incorrect setting of drupal.db.max_batch_size. Please check your code/settings.");
            return 0;
        }
    }

    /**
     * Get the "drush" executable from either -Ddrupal.drush, or DRUSH_EXEC string.
     * @return The drush executable if set, or "drush".
     * @deprecated use getDrushCommand() and getDrushSiteAlias() instead.
     */
    @Deprecated
    public String getDrushExec() {
        String defaultDrush = StringUtils.isEmpty(System.getenv("DRUSH_EXEC")) ? "drush" : System.getenv("DRUSH_EXEC");
        return properties.getProperty("drupal.drush", defaultDrush);
    }


    /**
     * @return The drush executable command.
     */
    public String getDrushCommand() {
        return this.getProperty("drupal.computing.drush.command", "drush");
    }

    /**
     * @return The default drush site alias. Users should include "@" in the config file.
     */
    public String getDrushSiteAlias() {
        return this.getProperty("drupal.computing.drush.site", "@self");
    }

    /**
     * Get the PHP executable from either -Ddrupal.php, or PHP_EXEC system variable.
     * @return PHP executable if set, or "php".
     */
    public String getPhpExec() {
        String defaultPhp = StringUtils.isEmpty(System.getenv("PHP_EXEC")) ? "php" : System.getenv("PHP_EXEC");
        return properties.getProperty("drupal.php", defaultPhp);
    }

    /**
     * Get the Xmlrpc endpoint setting.
     * @return the Xmlrpc endpoint setting. Or null if not set.
     */
    public String getXmlrpcEndpoint() {
        return properties.getProperty("drupal.xmlrpc.endpoint", null);
    }

    /**
     * Find the Drupal root. Or throws exception if can't find it.
     *
     * @return The location of Drupal root. Only local Drupal root is permitted. Remote drupal site will throw exception.
     * @throws DConfigException
     */
    public File getDrupalRoot() throws DConfigException {
        // get the system property first.
        String drupalRoot = this.getProperty("drupal.root", null);

        // if not set, try system env
        if (StringUtils.isBlank(drupalRoot)) {
            drupalRoot = System.getenv("DRUPAL_ROOT");
        }

        // if still not set, try drush
        if (StringUtils.isBlank(drupalRoot)) {
            DUtils.Drush drush = new DUtils.Drush(this.getDrushExec());
            try {
                drupalRoot = drush.execute(new String[]{"drupal-directory", "--local"}).trim();
            } catch (DSiteException e) {}
        }

        if (StringUtils.isBlank(drupalRoot)) {
            throw new DConfigException("Cannot find Drupal root.");
        } else {
            File drupalRootFile = new File(drupalRoot);
            if (drupalRootFile.exists() && drupalRootFile.isDirectory()) {
                return drupalRootFile.getAbsoluteFile();
            } else {
                throw new DConfigException("Drupal root does not exists or is not a directory.");
            }
        }
    }


    public static class UnitTest {

        @Test
        public void testDbProperties() throws Exception {
            DConfig config = new DConfig();
            config.setProperty("drupal.db.username", "scott");
            config.setProperty("drupal.db.password", "tiger");

            // test extractDbProperties()
            Properties p1 = config.extractDbProperties(config.properties);
            assertEquals("scott", p1.getProperty("username"));
            assertEquals("tiger", p1.getProperty("password"));

            // test convertDbProperties()
            p1.setProperty("driver", "pgsql");
            p1.setProperty("database", "test");
            p1.setProperty("host", "localhost");
            config.convertDbProperties(p1);
            assertEquals("jdbc:postgresql://localhost/test", p1.getProperty("url"));
            assertEquals("org.postgresql.Driver", p1.getProperty("driverClassName"));

            // test getDbPropertiesFromDrush()
            config.setProperty("drupal.drush", "drush @dev");
            Properties p2 = config.getDbPropertiesFromDrush(null);
            assertEquals("com.mysql.jdbc.Driver", p2.getProperty("driverClassName"));
            // make sure this exists.
            assertEquals("jdbc:mysql://localhost/openturk2", p2.getProperty("url"));
            assertTrue(StringUtils.isNotBlank(p2.getProperty("password")));

            // test getDbPropertiesFromSettings()
            //config.properties.remove("drupal.drush");
            //config.setProperty("drupal.settings.file", "/tmp/settings.php");
            config.setProperty("drupal.drush", "drush @local");   // should be able to find settings.php
            Properties p3 = config.getDbPropertiesFromSettings(null);
            assertEquals("jdbc:mysql://localhost/d7dev1", p3.getProperty("url"));

            // test overall.
            Properties dbProperties = config.getDbProperties();
            assertEquals("scott", dbProperties.getProperty("username"));
            assertEquals("com.mysql.jdbc.Driver", dbProperties.getProperty("driverClassName"));

            // test db identifier
            Properties extraDbProperties = config.getDbProperties("computing-extra");
            assertEquals("scott", extraDbProperties.getProperty("username"));
            assertEquals(DDatabase.DatabaseDriver.PGSQL.getJdbcDriver(), extraDbProperties.getProperty("driverClassName"));
        }

        @Test
        public void testMisc() throws Exception {
            DConfig config = new DConfig();

            config.setProperty("drupal.php", "php-cgi");
            assertEquals("php-cgi", config.getPhpExec());
            config.properties.remove("drupal.php");
            assertEquals("php", config.getPhpExec());

            config.setProperty("drupal.drush", "drush @local");
            assertEquals("drush @local", config.getDrushExec());
        }

        @Test
        public void testDrupalRoot() throws Exception {
            DConfig config = new DConfig();

            config.setProperty("drupal.root", "/Users/danithaca/Development/drupal7");
            assertEquals("/Users/danithaca/Development/drupal7", config.getDrupalRoot().getAbsolutePath());

            // test using drush to get drupal root
            config.properties.remove("drupal.root");
            config.setProperty("drupal.drush", "drush @local");
            try {
                assertEquals("/Users/danithaca/Development/drupal7", config.getDrupalRoot().getAbsolutePath().trim());
            } catch (DConfigException e) {
                //e.printStackTrace();
                fail("Drush execution problem.");
            }

            // using remote drush site would not get drupal root.
            config.setProperty("drupal.drush", "drush @dev");
            try {
                config.getDrupalRoot();
                fail("Remote drupal site returns no drupal root."); // we expect exception because of
            } catch (DConfigException e) {
                assertTrue(true);
            }
        }

        @Test
        public void testLocateFile() throws Exception {
            DConfig config = new DConfig();
            config.setProperty("drupal.drush", "drush @local");

            assertEquals("/Users/danithaca/Development/drupal7/sites/d7dev1.localhost/settings.php", config.locateFile("settings.php").getAbsolutePath());
            assertEquals("/Users/danithaca/Development/drupal7/sites/default/default.settings.php", config.locateFile("default.settings.php").getAbsolutePath());
            assertEquals("/Users/danithaca/.profile", config.locateFile(".profile").getAbsolutePath());

            try {
                config.locateFile("abc");
                fail("The file does not exists");
            } catch (FileNotFoundException e) {
                assertTrue(true);
            }
        }

        @Test
        public void testDbPropertiesExtra() throws Exception {
            DConfig config = new DConfig();
            config.setProperty("drupal.drush", "drush @local");
            config.setProperty("drupal.db.prefix", "computing");
            config.setProperty("drupal.db.max_batch_size", "500");

            assertEquals("computing", config.getDbPrefix());
            assertEquals(500, config.getMaxBatchSize());
        }
    }
}
