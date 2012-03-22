/*
 * Copyright 2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.grails.maven.plugin;

import grails.util.Metadata;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

import groovy.lang.GString;
import jline.Terminal;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.*;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.artifact.MavenMetadataSource;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.grails.launcher.GrailsLauncher;
import org.grails.launcher.RootLoader;
import org.grails.maven.plugin.tools.GrailsServices;

/**
 * Common services for all Mojos using Grails
 *
 * @author <a href="mailto:aheritier@gmail.com">Arnaud HERITIER</a>
 * @author Peter Ledbrook
 * @author Jonathan Pearlin
 * @version $Id$
 */
public abstract class AbstractGrailsMojo extends AbstractMojo {

  public static final String PLUGIN_PREFIX = "grails-";

  private static final String GRAILS_PLUGIN_NAME_FORMAT = "plugins.%s:%s";
  public static final String APP_GRAILS_VERSION = "app.grails.version";
  public static final String APP_VERSION = "app.version";

  /**
   * The directory where is launched the mvn command.
   *
   * @parameter default-value="${basedir}"
   * @required
   */
  protected File basedir;

  /**
   * The Grails environment to use.
   *
   * @parameter expression="${grails.env}"
   */
  protected String env;

  /**
   * Whether to run Grails in non-interactive mode or not. The default
   * is to run interactively, just like the Grails command-line.
   *
   * @parameter expression="${nonInteractive}" default-value="false"
   * @required
   */
  protected boolean nonInteractive;

  /**
   * The directory where plugins are stored.
   *
   * @parameter expression="${pluginsDirectory}" default-value="${basedir}/plugins"
   * @required
   */
  protected File pluginsDir;

  /**
   * The path to the Grails installation.
   *
   * @parameter expression="${grailsHome}"
   */
  protected File grailsHome;


  /**
   * The Maven settings reference.
   *
   * @parameter expression="${settings}"
   * @required
   * @readonly
   */
  protected Settings settings;

  /**
   * POM
   *
   * @parameter expression="${project}"
   * @readonly
   * @required
   */
  protected MavenProject project;

  /**
   * @component
   */
  private ArtifactResolver artifactResolver;

  /**
   * @component
   */
  private ArtifactFactory artifactFactory;

  /**
   * @component
   */
  private ArtifactCollector artifactCollector;

  /**
   * @component
   */
  private ArtifactMetadataSource artifactMetadataSource;

  /**
   * @parameter expression="${localRepository}"
   * @required
   * @readonly
   */
  private ArtifactRepository localRepository;

  /**
   * @parameter expression="${project.remoteArtifactRepositories}"
   * @required
   * @readonly
   */
  private List<?> remoteRepositories;

  /**
   * @component
   */
  private MavenProjectBuilder projectBuilder;

  /**
   * @component
   * @readonly
   */
  private GrailsServices grailsServices;


  /**
   * @parameter expression="${user.home}/.grails/maven"
   */
  private File centralPluginInstallDir;

  /**
   * If this is passed, it will set the grails.server.factory property.
   *
   * @parameter
   */
  private String servletServerFactory;


  /**
   * Returns the configured base directory for this execution of the plugin.
   *
   * @return The base directory.
   */
  protected File getBasedir() {
    if (basedir == null) {
      throw new RuntimeException("Your subclass have a field called 'basedir'. Remove it and use getBasedir() " +
        "instead.");
    }

    return this.basedir;
  }

  /**
   * Returns the {@code GrailsServices} instance used by the plugin with the base directory
   * of the services object set to the configured base directory.
   *
   * @return The underlying {@code GrailsServices} instance.
   */
  protected GrailsServices getGrailsServices() {
    grailsServices.setBasedir(basedir);
    return grailsServices;
  }

  protected void syncAppVersion() {
    final Metadata metadata = Metadata.getInstance(new File(getBasedir(), "application.properties"));
    
    syncVersion(metadata);
    
    metadata.persist();
  }
  
  /**
   * Executes the requested Grails target.  The "targetName" must match a known
   * Grails script provided by grails-scripts.
   *
   * @param targetName The name of the Grails target to execute.
   * @throws MojoExecutionException if an error occurs while attempting to execute the target.
   */
  protected void runGrails(final String targetName) throws MojoExecutionException {
    runGrails(targetName, null);
  }

  /**
   * Executes the requested Grails target.  The "targetName" must match a known
   * Grails script provided by grails-scripts.
   *
   * @param targetName The name of the Grails target to execute.
   * @param args       String of arguments to be passed to the executed Grails target.
   * @throws MojoExecutionException if an error occurs while attempting to execute the target.
   */
  protected void runGrails(final String targetName, String args) throws MojoExecutionException {

    getLog().info("Grails target: " + targetName + " raw args:" + args);

    InputStream currentIn = System.in;
    PrintStream currentOutput = System.out;

    // override the servlet factory if it is specified
    if (this.servletServerFactory != null) {
      System.setProperty("grails.server.factory", this.servletServerFactory);
    }

    // see if we are using logback and not log4j
    final String logbackFilename = this.getBasedir() + "/logback.xml";

    if (new File(logbackFilename).exists()) {
      getLog().info("Found logback configuration, setting logback.xml to " + logbackFilename);

      System.setProperty("logback.configurationFile", logbackFilename);
    }

    try {
      configureMavenProxy();

      // we only need to do this ONCE
      final Set<Artifact> resolvedArtifacts = collectAllProjectArtifacts();

      /*
      * Remove any Grails plugins that may be in the resolved artifact set.  This is because we
      * do not need them on the classpath, as they will be handled later on by a separate call to
      * "install" them.
      */
      final Set<Artifact> pluginArtifacts = removePluginArtifacts(resolvedArtifacts);


      final URL[] classpath = generateGrailsExecutionClasspath(resolvedArtifacts);

      final String grailsHomePath = (grailsHome != null) ? grailsHome.getAbsolutePath() : null;
      final RootLoader rootLoader = new RootLoader(classpath, ClassLoader.getSystemClassLoader());

      System.setProperty("grails.console.enable.terminal", "true");
      System.setProperty("grails.console.enable.interactive", "false");

      try {
        Class cls = rootLoader.loadClass("org.springframework.util.Log4jConfigurer");
        invokeStaticMethod(cls, "initLogging", new Object[]{"classpath:grails-maven/log4j.properties"});
        final GrailsLauncher launcher = new GrailsLauncher(rootLoader, grailsHomePath, basedir.getAbsolutePath());
        launcher.setPlainOutput(true);

        /**
         * this collects the different dependency levels (compile, runtime, test) and puts them into the correct arrays to pass through
         * to the Grails script launcher. If using Maven, you should *never* see an Ivy message and if you do, immediately stop your build, figure
         * out the incorrect dependency, delete the ~/.ivy2 directory and try again.
         */
        Field settingsField = launcher.getClass().getDeclaredField("settings");
        settingsField.setAccessible(true);

        configureBuildSettings(launcher, resolvedArtifacts, settingsField, rootLoader.loadClass("grails.util.BuildSettings"));

        // Search for all Grails plugin dependencies and install
        // any that haven't already been installed.
        final Metadata metadata = Metadata.getInstance(new File(getBasedir(), "application.properties"));
        syncGrailsVersion(metadata);
        syncVersion(metadata);

        for (Artifact artifact : pluginArtifacts) {
          installGrailsPlugin(artifact, metadata, launcher, settingsField, rootLoader.loadClass("grails.util.AbstractBuildSettings"));
        }

        // always update application.properties - version control systems are clever enough to know when a file hasn't actually changed its content
        // so there is no reason to not write this every time. This will cause a failure if you don't manually change the application.properties file
        // when doing a release:prepare

        metadata.persist();


        // If the command is running in non-interactive mode, we
        // need to pass on the relevant argument.
        if (this.nonInteractive) {
          args = (args != null) ? "--non-interactive" + args : "--non-interactive ";
        }

        // Enable the plain output for the Grails command to fix an issue with JLine
        // consuming the standard output after execution via Maven.
        args = (args != null) ? "--plain-output " + args : "--plain-output";

        final int retval = launcher.launch(targetName, args, env);

        if ("true".equals(System.getProperty("print.grails.settings")))
          printIntellijIDEASettings(launcher, settingsField, pluginArtifacts);

        if (retval != 0) {
          throw new MojoExecutionException("Grails returned non-zero value: " + retval);
        }
      } catch (final MojoExecutionException ex) {
        // Simply rethrow it.
        throw ex;
      } catch (final Exception ex) {
        getLog().error(ex);
        ex.printStackTrace();
        throw new MojoExecutionException("Unable to start Grails", ex);
      }
    } finally {
      Terminal.resetTerminal();
      System.setIn(currentIn);
      System.setOut(currentOutput);
    }
  }


  public static final String SETTINGS_START_MARKER = "---=== IDEA Grails build settings ===---";
  public static final String SETTINGS_END_MARKER = "---=== End IDEA Grails build settings ===---";

  private static Set GRAILS_PROPERTY_LIST = new HashSet(Arrays.asList(new String[]{"grails.work.dir", "grails.project.work.dir",
    "grails.project.target.dir", "grails.project.war.file", "grails.project.war.exploded.dir", "grails.project.class.dir",
    "grails.project.test.class.dir", "grails.project.resource.dir", "grails.project.source.dir", "grails.project.web.xml",
    "grails.project.plugins.dir", "grails.global.plugins.dir", "grails.project.test.reports.dir", "grails.project.test.source.dir"}));

  private void printIntellijIDEASettings(GrailsLauncher launcher, Field settingsField, Set<Artifact> pluginArtifacts) {
    try {
      Object settings = settingsField.get(launcher);
      Field configField = settings.getClass().getSuperclass().getDeclaredField("config");
      configField.setAccessible(true);
      Object config = configField.get(settings);
      Map flatten = (Map) config.getClass().getDeclaredMethod("flatten").invoke(config);

      System.out.println();
      System.out.println(SETTINGS_START_MARKER);

      for (Object key : flatten.keySet()) {
        Object value = flatten.get(key);
        if (value instanceof String || value instanceof GString) {
          String realKey = key.toString();
          if (GRAILS_PROPERTY_LIST.contains(realKey)) {
            System.out.println(realKey + "=" + value.toString());
          }
        }
      }
      
      for(Artifact plugin: pluginArtifacts) {
        File targetDir = getPluginTargetDir(plugin);
        System.out.println("grails.plugin.location." + getPluginName(plugin) + "=" + targetDir.getAbsolutePath());
      }

      System.out.println();
      System.out.println(SETTINGS_END_MARKER);
    } catch (Exception ex) {
      getLog().error("Unable to get flattened configuration data", ex);
    }
  }

  private void syncVersion(Metadata metadata) {
    metadata.put(APP_VERSION, project.getVersion());
  }


  private boolean syncGrailsVersion(Metadata metadata) {
    Object grailsVersion = metadata.get(APP_GRAILS_VERSION);

    Artifact grailsDependency = findGrailsDependency(project);
    if (grailsDependency != null) {
      if (!grailsDependency.getVersion().equals(grailsVersion)) {
        metadata.put(APP_GRAILS_VERSION, grailsDependency.getVersion());
        return true;
      }
    }
    return false;
  }

  private Artifact findGrailsDependency(MavenProject project) {
    Set dependencyArtifacts = project.getDependencyArtifacts();
    for (Object o : dependencyArtifacts) {
      Artifact artifact = (Artifact) o;
      if (artifact.getArtifactId().equals("grails-dependencies")) {
        return artifact;
      }
    }
    return null;
  }


  private void configureMavenProxy() {
    if (settings != null) {
      Proxy activeProxy = settings.getActiveProxy();
      if (activeProxy != null) {
        String host = activeProxy.getHost();
        int port = activeProxy.getPort();
        String username = activeProxy.getUsername();
        String password = activeProxy.getPassword();

        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", String.valueOf(port));
        if (username != null) {
          System.setProperty("http.proxyUser", username);
        }
        if (password != null) {
          System.setProperty("http.proxyPassword", password);
        }
      }
    }
  }

  /**
   * Invokes the named method on a target object using reflection.
   * The method signature is determined by the classes of each argument.
   *
   * @param target The object to call the method on.
   * @param name   The name of the method to call.
   * @param args   The arguments to pass to the method (may be an empty array).
   * @return The value returned by the method.
   */
  private Object invokeStaticMethod(Class target, String name, Object[] args) {
    Class<?>[] argTypes = new Class[args.length];
    for (int i = 0; i < args.length; i++) {
      argTypes[i] = args[i].getClass();
    }

    try {
      return target.getMethod(name, argTypes).invoke(target, args);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  /*
  taken from the grails launcher as the plugins setup is broken
   */
  private Object invokeMethod(Object target, String name, Class<?>[] argTypes, Object[] args) {
    try {
      return target.getClass().getMethod(name, argTypes).invoke(target, args);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private Set<Artifact> collectAllProjectArtifacts() throws MojoExecutionException {
    final List<Dependency> unresolvedDependencies = new ArrayList<Dependency>();

    /*
    * Get the Grails dependencies from the plugin's POM file first.
    */
    final MavenProject pluginProject;
    try {
      pluginProject = getPluginProject();
    } catch (ProjectBuildingException e) {
      throw new MojoExecutionException("Unable to get plugin project", e);
    }

    /*
    * Add the plugin's dependencies and the project using the plugin's dependencies to the list
    * of unresolved dependencies.  This is done so they can all be resolved at the same time so
    * that we get the benefit of Maven's conflict resolution.
    */
    unresolvedDependencies.addAll(this.project.getDependencies());

    unresolvedDependencies.addAll(filterForGrailsDependencies(pluginProject.getDependencies(), "org.grails"));

    return getResolvedArtifactsFromUnresolvedDependencies(unresolvedDependencies);
  }


  /**
   * Generates the classpath to be used by the launcher to execute the requested Grails script.
   *
   * @return An array of {@code URL} objects representing the dependencies required on the classpath to
   *         execute the selected Grails script.
   * @throws MojoExecutionException if an error occurs while attempting to resolve the dependencies and
   *                                generate the classpath array.
   */
  @SuppressWarnings("unchecked")
  private URL[] generateGrailsExecutionClasspath(Set<Artifact> resolvedArtifacts) throws MojoExecutionException {
    try {


      /*
      * Convert each resolved artifact into a URL/classpath element.
      */
      final List<URL> classpath = new ArrayList<URL>();
      int index = 0;
      for (Artifact resolvedArtifact : resolvedArtifacts) {
        final File file = resolvedArtifact.getFile();
        if (file != null) {
          classpath.add(file.toURI().toURL());
        }
      }
      
      // check to see if someone is adding build listeners on the classpath, and if so, bring in the system classpath and add it to our urls
      // IDEA for example does this
      if (System.getProperty("grails.build.listeners") != null) {
        String cp = System.getProperty("java.class.path");
        for( String c : cp.split(":") ) {
          File f = new File(c);
          if (f.exists())
            classpath.add(f.toURI().toURL());
        }
      }
      
      for (URL url : classpath) {
        getLog().debug("classpath " + url.toString());
      }

      /*
      * Add the "tools.jar" to the classpath so that the Grails scripts can run native2ascii.
      * First assume that "java.home" points to a JRE within a JDK.  NOTE that this will not
      * provide a valid path on Mac OSX.  This is not a big deal, as the JDK on Mac OSX already
      * adds the required JAR's to the classpath.  This logic is really only for Windows/*Unix.
      */
      final String javaHome = System.getProperty("java.home");
      File toolsJar = new File(javaHome, "../lib/tools.jar");
      if (!toolsJar.exists()) {
        // The "tools.jar" cannot be found with that path, so
        // now try with the assumption that "java.home" points
        // to a JDK.
        toolsJar = new File(javaHome, "tools.jar");
      }
      if (toolsJar.exists()) {
        java.net.URL url = toolsJar.toURI().toURL();
        if (url != null) {
          classpath.add(url);
        }
      }
      return classpath.toArray(new URL[classpath.size()]);
    } catch (final Exception e) {
      throw new MojoExecutionException("Failed to create classpath for Grails execution.", e);
    }
  }

  private MavenProject getPluginProject() throws ProjectBuildingException {
    Artifact pluginArtifact = findArtifact(this.project.getPluginArtifacts(), "com.bluetrainsoftware.bluegrails", "grails-maven-plugin");
    if (pluginArtifact == null)
      pluginArtifact = findArtifact(this.project.getPluginArtifacts(), "org.grails", "grails-dependencies");
    return this.projectBuilder.buildFromRepository(pluginArtifact, this.remoteRepositories, this.localRepository);
  }

  /**
   * Returns only the dependencies matching the supplied group ID value, filtering out
   * all others.
   *
   * @param dependencies A list of dependencies to be filtered.
   * @param groupId      The group ID of the requested dependencies.
   * @return The filtered list of dependencies.
   */
  private List<Dependency> filterForGrailsDependencies(final List<Dependency> dependencies, final String groupId) {
    final List<Dependency> filteredDependencies = new ArrayList<Dependency>();
    for (final Dependency dependency : dependencies) {
      if (dependency.getGroupId().equals(groupId) && !"grails-dependencies".equals(dependency.getArtifactId())) {
        filteredDependencies.add(dependency);
      }
    }
    return filteredDependencies;
  }


  Set<Artifact> getResolvedArtifactsFromUnresolvedDependencies(List<Dependency> unresolvedDependencies) throws MojoExecutionException {
    final Set<Artifact> resolvedArtifacts = new HashSet<Artifact>();
    Artifact mojoArtifact = this.artifactFactory.createBuildArtifact("com.bluetrainsoftware.bluegrails", "maven-project", "1", "pom");

    /*
    * Resolve each artifact.  This will get all transitive artifacts AND eliminate conflicts.
    */
    final Set<Artifact> unresolvedArtifacts;

//    for (Dependency d : unresolvedDependencies)
//      System.out.println("dependency: " + d.toString());

    try {
      unresolvedArtifacts = MavenMetadataSource.createArtifacts(this.artifactFactory, unresolvedDependencies, null, null, null);

      resolvedArtifacts.addAll(artifactResolver.resolveTransitively(unresolvedArtifacts, mojoArtifact,
        remoteRepositories, localRepository, artifactMetadataSource).getArtifacts());
    } catch (Exception e) {
      throw new MojoExecutionException("Unable to complete configuring the build settings", e);
    }

//    for (Artifact artifact : resolvedArtifacts) {
//      System.out.println("matched " + artifact.toString());
//    }

    return resolvedArtifacts;
  }

  /**
   * Configures the launcher for execution.
   *
   * @param launcher The {@code GrailsLauncher} instance to be configured.
   */
  @SuppressWarnings("unchecked")
  private Set<Artifact> configureBuildSettings(final GrailsLauncher launcher, Set<Artifact> resolvedArtifacts, Field settingsField, Class clazz) throws ProjectBuildingException, MojoExecutionException {
    final String targetDir = this.project.getBuild().getDirectory();
    launcher.setDependenciesExternallyConfigured(true);
    launcher.setCompileDependencies(artifactsToFiles(this.project.getCompileArtifacts()));
    launcher.setTestDependencies(artifactsToFiles(this.project.getTestArtifacts()));
    launcher.setRuntimeDependencies(artifactsToFiles(this.project.getRuntimeArtifacts()));
    launcher.setProjectWorkDir(new File(targetDir));
    launcher.setClassesDir(new File(targetDir, "classes"));
    launcher.setTestClassesDir(new File(targetDir, "test-classes"));
    launcher.setResourcesDir(new File(targetDir, "resources"));
    launcher.setProjectPluginsDir(this.pluginsDir);

    List<File> files = artifactsToFiles(resolvedArtifacts);

    launcher.setBuildDependencies(files);

    Object settings = null;
    try {
      settings = settingsField.get(launcher);

      Field f = settings.getClass().getDeclaredField("defaultPluginSet");
      f.setAccessible(true);
      f.set(settings, new HashSet());
      f = settings.getClass().getDeclaredField("defaultPluginMap");
      f.setAccessible(true);
      f.set(settings, new LinkedHashMap());
      f = settings.getClass().getDeclaredField("enableResolve");
      f.setAccessible(true);
      f.set(settings, false);

    } catch (Exception e) {
      getLog().error("Unable to set default plugin set to empty ", e);
    }

    return resolvedArtifacts;
  }

  
  private String getPluginName(Artifact plugin) {
    String pluginName = plugin.getArtifactId();
    
    if (pluginName.startsWith(PLUGIN_PREFIX)) {
      return pluginName.substring(PLUGIN_PREFIX.length());
    } else {
      return pluginName;
    }
  }
  
  private File getPluginTargetDir(Artifact plugin) {
    String pluginLocationOverride = System.getProperty(plugin.getGroupId() + ":" + plugin.getArtifactId());

    File targetDir = null;

    if (pluginLocationOverride != null && pluginLocationOverride.length() > 0) {
      targetDir = new File(pluginLocationOverride);
      if (!targetDir.exists()) {
        getLog().error(String.format("Specified directory (%s) for plugin %s:%s:%s could not be found", pluginLocationOverride, plugin.getGroupId(), plugin.getArtifactId(), plugin.getVersion()));
        targetDir = null;
      }
    }

    if (targetDir == null) {
      // The directory the plugin will be unzipped to.
      targetDir = new File(this.centralPluginInstallDir, getPluginName(plugin) + "-" + plugin.getVersion());
    }

    return targetDir;
  }

  
  /**
   * Installs a Grails plugin into the current project if it isn't
   * already installed. It works by simply unpacking the plugin
   * artifact (a ZIP file) into the appropriate location and adding
   * the plugin to the application's metadata.
   *
   * @param plugin   The plugin artifact to install.
   * @param metadata The application metadata. An entry for the plugin
   *                 is added to this if the installation is successful.
   * @param launcher The launcher instance that contains information about
   *                 the various project directories. In particular, this is where the
   *                 method gets the location of the project's "plugins" directory
   *                 from.
   * @return <code>true</code> if the plugin is installed and the
   *         metadata updated, otherwise <code>false</code>.
   * @throws IOException
   * @throws ArchiverException
   */
  private boolean installGrailsPlugin(
    final Artifact plugin,
    final Metadata metadata,
    final GrailsLauncher launcher,
    Field settingsField,
    Class clazz) throws IOException, ArchiverException {
    
    
    File targetDir = getPluginTargetDir(plugin);
    
    String pluginName = getPluginName(plugin);
    final String pluginVersion = plugin.getVersion();

    // Unpack the plugin if it hasn't already been.
    if (!targetDir.exists()) {
      // Ideally we need to now do two things (a) see if we are running JDK7
      // and (b) determine if -Dplugin.groupId.artifactId has been set - if this is so, we want to do a Files.createLink
      // to the directory specified by  the -D flag. We should probably also check if the targetDir is a link and
      // the -Dflag hasn't been set, in which case we'd want to remove the link and install the plugin (and let the user
      // know this has happened.
      // We wouldn't actually want this to be allowed when doing a release however.... So people should make sure they don't
      // specify them, they they'll be installed.
      getLog().info(String.format("Installing Plugin %s:%s into (%s)", pluginName, pluginVersion, targetDir.getAbsolutePath()));
      targetDir.mkdirs();

      final ZipUnArchiver unzipper = new ZipUnArchiver();
      unzipper.enableLogging(new ConsoleLogger(Logger.LEVEL_ERROR, "zip-unarchiver"));
      unzipper.setSourceFile(plugin.getFile());
      unzipper.setDestDirectory(targetDir);
      unzipper.setOverwrite(true);
      unzipper.extract();
    } else {
      getLog().info(String.format("Plugin %s:%s already installed (%s)", pluginName, pluginVersion, targetDir.getAbsolutePath()));
    }

    // Now add it to the application metadata.
//      getLog().debug("Updating project metadata");
//      metadata.setProperty(String.format(GRAILS_PLUGIN_NAME_FORMAT, plugin.getGroupId(), pluginName), pluginVersion);
//      metadata.setProperty("plugins." + pluginName, pluginVersion);


    Object settings = null;
    try {
      settings = settingsField.get(launcher);

      Method m = clazz.getDeclaredMethod("addPluginDirectory", new Class[]{File.class, boolean.class});
      m.invoke(settings, targetDir, true);

    } catch (Exception e) {
      getLog().error("Unable to install plugin " + pluginName, e);
    }

    return false;
  }

  /**
   * Converts a collection of Maven artifacts to files.  For this method to function properly,
   * the artifacts MUST be resolved first.
   *
   * @param artifacts A collection of artifacts.
   * @return The list of files pointed to by the artifacts.
   */
  private List<File> artifactsToFiles(final Collection<Artifact> artifacts) {
    final List<File> files = new ArrayList<File>(artifacts.size());
    for (Artifact artifact : artifacts) {
      files.add(artifact.getFile());
    }

    return files;
  }

  /**
   * Finds the requested artifact in the supplied artifact collection.
   *
   * @param artifacts  A collection of artifacts.
   * @param groupId    The group ID of the artifact to be found.
   * @param artifactId The artifact ID of the artifact to be found.
   * @return The artifact from the collection that matches the group ID and
   *         artifact ID value or {@code null} if no match is found.
   */
  private Artifact findArtifact(final Collection<Artifact> artifacts, final String groupId, final String artifactId) {
    for (final Artifact artifact : artifacts) {
      if (artifact.getGroupId().equals(groupId) && artifact.getArtifactId().equals(artifactId)) {
        return artifact;
      }
    }

    return null;
  }

  /**
   * Converts a collection of Dependency objects to a list of
   * corresponding Artifact objects.
   *
   * @param deps The collection of dependencies to convert.
   * @return A list of Artifact instances.
   */
  private List<Artifact> dependenciesToArtifacts(final Collection<Dependency> deps) {
    final List<Artifact> artifacts = new ArrayList<Artifact>(deps.size());
    for (Dependency dep : deps) {
      artifacts.add(dependencyToArtifact(dep));
    }

    return artifacts;
  }

  /**
   * Uses the injected artifact factory to convert a single Dependency
   * object into an Artifact instance.
   *
   * @param dep The dependency to convert.
   * @return The resulting Artifact.
   */
  private Artifact dependencyToArtifact(final Dependency dep) {
//    return this.artifactFactory.createBuildArtifact(
//      dep.getGroupId(),
//      dep.getArtifactId(),
//      dep.getVersion(),
//      "pom");
    return this.artifactFactory.createDependencyArtifact(dep.getGroupId(), dep.getArtifactId(), VersionRange.createFromVersion(dep.getVersion()),
      dep.getType(), dep.getClassifier(), dep.getScope());
  }

  /**
   * Removes any Grails plugin artifacts from the supplied list
   * of dependencies.  A Grails plugin is any artifact whose type
   * is equal to "grails-plugin" or "zip"
   *
   * @param artifact The list of artifacts to be cleansed.
   * @return list of plugins
   */
  private Set<Artifact> removePluginArtifacts(final Set<Artifact> artifact) {
    final Set<Artifact> pluginArtifacts = new HashSet<Artifact>();

    if (artifact != null) {
      for (final Iterator<Artifact> iter = artifact.iterator(); iter.hasNext(); ) {
        final Artifact dep = iter.next();
        if (dep.getType() != null && (dep.getType().equals("grails-plugin") || dep.getType().equals("zip"))) {
          pluginArtifacts.add(dep);
//          System.out.println("removing " + dep.toString());
          iter.remove();
        }
      }
    }

    return pluginArtifacts;
  }
}
