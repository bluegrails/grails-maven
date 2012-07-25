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

import grails.util.GrailsNameUtils;
import grails.util.Metadata;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

import groovy.lang.GString;
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
  private static final String GRAILS_PLUGIN_VERSION_PATTERN = "((def|String)\\s*version\\s*=\\s*(\"|'))(.*)(\"|')";

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
   * We want this to come from the main pom so we can re-write the dependencies of the plugin to match
   *
   * @parameter expression="${grails.version}
   */

  private String grailsVersion;

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

  private String readFileAsString( File file ) throws java.io.IOException {
    byte[] buffer = new byte[ (int) file.length() ];
    BufferedInputStream bis = null;
    try {
      bis = new BufferedInputStream( new FileInputStream( file ) );
      bis.read( buffer );
    } finally {
      if( bis != null ) try {
        bis.close();
      } catch ( IOException ignored ) {
      }
    }
    return new String( buffer );
  }

  protected void syncAppVersion() {
    final Metadata metadata = Metadata.getInstance(new File(getBasedir(), "application.properties"));
    if (syncVersion(metadata))
      metadata.persist();

    String artifactId = project.getArtifactId();

    if (artifactId.startsWith("grails-"))
      artifactId = artifactId.substring("grails-".length());
    
    final String fName = this.getBasedir() + File.separator + GrailsNameUtils.getNameFromScript( artifactId ) + "GrailsPlugin.groovy";
    File gpFile = new File( fName );
    if ( gpFile.exists() ) {
      String text = null;
      String mod = null;
      try {
        text = readFileAsString( gpFile );
        mod = text.replaceFirst(GRAILS_PLUGIN_VERSION_PATTERN, "$1"+project.getVersion()+"$5");
      } catch ( IOException e ) {
        // ignore
      }
      if ( text != null && !mod.equalsIgnoreCase(text) ){
        BufferedOutputStream out = null;
        try {
          out = new BufferedOutputStream(new FileOutputStream( gpFile ) );
          out.write(mod.getBytes());
        }
        catch (IOException e) {
          // do nuffink
        }
        finally {
          if ( out != null) {
            try {
              out.close();
            }
            catch (Exception ignored) {
              // ignored
            }
          }
        }

      }

    }

  }


  private boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().indexOf("windows") != -1;
  }

  // we are getting a situation where the same goal is running twice - two compiles, two test apps. This is a hack fix until the real cause is discovered.
  private static String lastTargetName;
  private static String lastArgs;

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


  private static ClassLoader jlineClassloaderParent;

  protected void initializeJline() throws MojoExecutionException {
    try {
      if (jlineClassloaderParent == null) {

        List<URL> classpath = generateExecutionClasspath(
          getResolvedArtifactsFromUnresolvedDependencies(
            filterDependencies(getPluginProject().getDependencies(), Arrays.asList("jline")), "com.bluetrainsoftware.bluegrails", "jline-parent"));

        jlineClassloaderParent = new RootLoader(classpath.toArray(new URL[classpath.size()]), ClassLoader.getSystemClassLoader());
      }
    } catch (ProjectBuildingException pbe) {
      throw new MojoExecutionException("Unable to load plugin project", pbe);
    }
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
    if (((lastArgs != null && lastArgs.equals(args)) || (lastArgs == null && args == null)) && lastTargetName != null && lastTargetName.equals(targetName))
      return;

    lastArgs = args;
    lastTargetName = targetName;

    getLog().info("Grails target: " + targetName + " raw args:" + args + " (pom says Grails Version is " + grailsVersion + ")");

    // hold onto it as it holds the jLine.Terminal class we need to reset the terminal back to normal. We have to do it this
    // way as on Windows it fails as we hold a ref and the Grails class loader holds a ref, JLine tries to duplicate load the
    // Windows DLL.
    initializeJline();

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
      RootLoader rootLoader = new RootLoader(classpath, jlineClassloaderParent);


      if (isWindows()) { // force console and interactive on to get around _GrailsRun.groovy windows bug where attaches to grailsConsole.reader.add...
        System.setProperty("grails.console.enable.terminal", "true");
        System.setProperty("grails.console.enable.interactive", "true");
      } else {
        if (System.getProperty("grails.console.enable.terminal") == null)
          System.setProperty("grails.console.enable.terminal", "false");
        if (System.getProperty("grails.console.enable.interactive") == null)
          System.setProperty("grails.console.enable.interactive", "false");
      }

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

        configureBuildSettings(launcher, resolvedArtifacts, settingsField, rootLoader.loadClass("grails.util.BuildSettings"), args);

        syncAppVersion();

        for (Artifact artifact : pluginArtifacts) {
          installGrailsPlugin(artifact, launcher, settingsField, rootLoader.loadClass("grails.util.AbstractBuildSettings"));
        }

        // always update application.properties - version control systems are clever enough to know when a file hasn't actually changed its content
        // so there is no reason to not write this every time. This will cause a failure if you don't manually change the application.properties file
        // when doing a release:prepare




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
      if (jlineClassloaderParent != null) {
        try {
          Class cls = jlineClassloaderParent.loadClass("jline.Terminal");
          invokeStaticMethod(cls, "resetTerminal", new Object[]{});
        } catch (Exception ex) {
        }
      }
      System.setIn(currentIn);
      System.setOut(currentOutput);
    }

    System.gc(); // try and help with memory issues
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
            System.out.println(realKey + "=" + value.toString().replace('\\', '/'));
          }
        }
      }

      for (Artifact plugin : pluginArtifacts) {
        File targetDir = getPluginTargetDir(plugin);
        System.out.println("grails.plugin.location." + getPluginName(plugin) + "=" + targetDir.getAbsolutePath().replace('\\', '/'));
      }

      System.out.println();
      System.out.println(SETTINGS_END_MARKER);
    } catch (Exception ex) {
      getLog().error("Unable to get flattened configuration data", ex);
    }
  }

  private boolean syncVersion(Metadata metadata) {
    boolean changed = false;

    Object apGrailsVersion = metadata.get(APP_GRAILS_VERSION);

    Artifact grailsDependency = findGrailsDependency(project);
    if (grailsDependency != null) {
      if (!grailsDependency.getVersion().equals(apGrailsVersion)) {
        metadata.put(APP_GRAILS_VERSION, grailsDependency.getVersion());
        changed = true;
      }
    } else if (grailsVersion != null && !grailsVersion.equals(apGrailsVersion)) {
      metadata.put(APP_GRAILS_VERSION, grailsVersion);
      changed = true;
    }

    if (!project.getVersion().equals(metadata.get(APP_VERSION))) {
      metadata.put(APP_VERSION, project.getVersion());
      changed = true;
    }

    return changed;
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

    unresolvedDependencies.addAll(replaceVersion(filterDependencies(pluginProject.getDependencies(), Arrays.asList("org.grails"))));

    return getResolvedArtifactsFromUnresolvedDependencies(unresolvedDependencies);
  }

  private Collection<Dependency> replaceVersion(List<Dependency> dependencies) {
    if (grailsVersion != null) {
      for(Dependency d : dependencies) {
        if ("org.grails".equals(d.getGroupId()) && !grailsVersion.equals(d.getVersion()) && grailsVersion.charAt(0) == d.getVersion().charAt(0)) {
          d.setVersion( grailsVersion );
        }
      }
    }
    
    return dependencies;
  }


  private List<URL> generateExecutionClasspath(Set<Artifact> resolvedArtifacts) throws MojoExecutionException {
    /*
    * Convert each resolved artifact into a URL/classpath element.
    */
    final List<URL> classpath = new ArrayList<URL>();

    try {

      for (Artifact resolvedArtifact : resolvedArtifacts) {
        final File file = resolvedArtifact.getFile();
        if (file != null) {
          classpath.add(file.toURI().toURL());
        }
      }
    } catch (MalformedURLException murle) {
      throw new MojoExecutionException("Unable to find files", murle);
    }

    return classpath;
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

      final List<URL> classpath = generateExecutionClasspath(resolvedArtifacts);

      // check to see if someone is adding build listeners on the classpath, and if so, bring in the system classpath and add it to our urls
      // IDEA for example does this
      if (System.getProperty("grails.build.listeners") != null) {
        String cp = System.getProperty("java.class.path");
        for (String c : cp.split(":")) {
          File f = new File(c);
          if (f.exists())
            classpath.add(f.toURI().toURL());
        }
      }

//      for (URL url : classpath) {
//        getLog().debug("classpath " + url.toString());
//      }

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
   * @param groupIds     The group IDs of the requested dependencies.
   * @return The filtered list of dependencies.
   */
  private List<Dependency> filterDependencies(final List<Dependency> dependencies, List<String> groupIds) {
    final List<Dependency> filteredDependencies = new ArrayList<Dependency>();
    for (final Dependency dependency : dependencies) {
      if (groupIds.contains(dependency.getGroupId()) && !"grails-dependencies".equals(dependency.getArtifactId())) {
        filteredDependencies.add(dependency);
      }
    }
    return filteredDependencies;
  }


  Set<Artifact> getResolvedArtifactsFromUnresolvedDependencies(List<Dependency> unresolvedDependencies, String groupId, String artifactId) throws MojoExecutionException {
    final Set<Artifact> resolvedArtifacts = new HashSet<Artifact>();
    Artifact mojoArtifact = this.artifactFactory.createBuildArtifact(groupId, artifactId, "1", "pom");

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

  Set<Artifact> getResolvedArtifactsFromUnresolvedDependencies(List<Dependency> unresolvedDependencies) throws MojoExecutionException {
    return getResolvedArtifactsFromUnresolvedDependencies(unresolvedDependencies, "com.bluetrainsoftware.bluegrails", "maven-project");
  }


  private boolean logDependencies = false;

  /**
   * Configures the launcher for execution.
   *
   * @param launcher The {@code GrailsLauncher} instance to be configured.
   */
  @SuppressWarnings("unchecked")
  private Set<Artifact> configureBuildSettings(final GrailsLauncher launcher, Set<Artifact> resolvedArtifacts, Field settingsField, Class clazz, String args) throws ProjectBuildingException, MojoExecutionException {
    final String targetDir = this.project.getBuild().getDirectory();
    launcher.setDependenciesExternallyConfigured(true);

    // allow plugins that are being developed with fake api implementations to include the test artifacts in the runtime
    if (args != null && args.contains("--run-with-test-dependencies")) {
      List<File> artifacts = artifactsToFiles(filterArtifacts(resolvedArtifacts, "compile", "runtime", "test"));
      launcher.setCompileDependencies(artifacts);
      launcher.setRuntimeDependencies(artifacts);
      launcher.setTestDependencies(artifacts);
    } else {
      // getCompileArtifacts, getRuntimeArtifacts and getTestArticats on the project are not reliable
      logDependencies = "true".equals(System.getProperty("grails.maven.dependencies.compile"));
      launcher.setCompileDependencies(artifactsToFiles(filterArtifacts(resolvedArtifacts, "compile")));
      logDependencies = "true".equals(System.getProperty("grails.maven.dependencies.runtime"));
      launcher.setRuntimeDependencies(artifactsToFiles(filterArtifacts(resolvedArtifacts, "compile", "runtime")));
      logDependencies = "true".equals(System.getProperty("grails.maven.dependencies.test"));
      launcher.setTestDependencies(artifactsToFiles(filterArtifacts(resolvedArtifacts, "compile", "runtime", "test")));
      logDependencies = false;
    }


    launcher.setProjectWorkDir(new File(targetDir));
    launcher.setClassesDir(new File(targetDir, "classes"));
    launcher.setTestClassesDir(new File(targetDir, "test-classes"));
    launcher.setResourcesDir(new File(targetDir, "resources"));
    launcher.setProjectPluginsDir(this.pluginsDir);

    logDependencies = "true".equals(System.getProperty("grails.maven.dependencies.build"));
    List<File> files = artifactsToFiles(resolvedArtifacts);
    logDependencies = false;

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

  private Set<Artifact> filterArtifacts(Set<Artifact> resolvedArtifacts, String... scopes) {
    HashSet<Artifact> artifacts = new HashSet<Artifact>();
    List<String> checkScopes = Arrays.asList(scopes);

    for(Artifact artifact : resolvedArtifacts) {
      if (checkScopes.contains(artifact.getScope()))
        artifacts.add(artifact);
    }

    return artifacts;
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
    StringBuilder sb = new StringBuilder();
    for (Artifact artifact : artifacts) {
      sb.append("\natof " + artifact.getFile().getAbsolutePath());
      files.add(artifact.getFile());
    }

    if (logDependencies) getLog().info(sb.toString());

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
