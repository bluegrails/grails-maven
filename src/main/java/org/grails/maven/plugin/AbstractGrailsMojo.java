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
import groovy.lang.GString;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.*;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.artifact.MavenMetadataSource;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilder;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilderException;
import org.apache.maven.shared.dependency.tree.traversal.DependencyNodeVisitor;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.grails.launcher.RootLoader;
import org.grails.maven.plugin.tools.DecentGrailsLauncher;
import org.grails.maven.plugin.tools.GrailsServices;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

/**
 * Common services for all Mojos using Grails.
 *
 * This should be re-written in Groovy (static)
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
   */
  @Parameter(defaultValue = "${basedir}", required = true)
  protected File basedir;

  /**
   * The Grails environment to use.
   *
   */
  @Parameter(property = "grails.env")
  protected String env;

  /**
   * Whether to run Grails in non-interactive mode or not. The default
   * is to run interactively, just like the Grails command-line.
   *
   */
  @Parameter(property = "nonInteractive", defaultValue = "true", required = true)
  protected boolean nonInteractive;

  /**
   * The directory where plugins are stored.
   *
   */
  @Parameter(property = "pluginsDirectory", defaultValue = "${basedir}/plugins", required = true)
  protected File pluginsDir;

  /**
   * The path to the Grails installation.
   *
   */
  @Parameter(property = "grailsHome")
  protected File grailsHome;


  /**
   * The Maven settings reference.
   *
   */
  @Parameter(property = "settings", required = true, readonly = true)
  protected Settings settings;

  /**
   * POM
   *
   */
  @Parameter(property = "project", readonly = true, required = true)
  protected MavenProject project;

  @Component
  private ArtifactResolver artifactResolver;

  /**
   */
  @Component
  private ArtifactFactory artifactFactory;

  /**
   */
  @Component
  private ArtifactMetadataSource artifactMetadataSource;

  @Parameter(property = "localRepository")
  private ArtifactRepository localRepository;

  /**
   * The artifact collector to use.
   *
   */
  @Component
  private ArtifactCollector artifactCollector;

  /**
   * The dependency tree builder to use.
   *
   */
  @Component
  private DependencyTreeBuilder dependencyTreeBuilder;
  /**
   */
  @Parameter(property = "project.remoteArtifactRepositories", readonly = true, required = true)
  private List<ArtifactRepository> remoteRepositories;

  /**
   */
  @Component
  private MavenProjectBuilder projectBuilder;

  /**
   */
  @Component
  private GrailsServices grailsServices;


  /**
   */
  @Parameter(defaultValue = "${user.home}/.grails/maven")
  private File centralPluginInstallDir;

  /**
   * If this is passed, it will set the grails.server.factory property.
   *
   */
  @Parameter
  private String servletServerFactory;

  /**
   * We want this to come from the main pom so we can re-write the dependencies of the plugin to match
   *
   */

  @Parameter(property = "grails.version")
  private String grailsVersion;


  @Parameter(property = "run.useTransitives")
  private boolean useTransitives = true; // by default use Maven 2.x to resolve transitives

	/**
	 * Whether we want the test dependencies to be used as run dependencies
	 */
	@Parameter(property = "run.includeTestDependencies")
	protected boolean runWithTestDependencies = false;

	/**
	 * Whether we want the execute the unit tests
	 */
	@Parameter(property = "run.unitTest")
	protected boolean runUnitTests = true;

	/**
	 * Whether we want the execute the unit tests
	 */
	@Parameter(property = "run.integrationTest")
	protected boolean runIntegrationTests = true;

	/**
	 * Whether we want the execute the unit tests
	 */
	@Parameter(property = "run.functionalTest")
	protected boolean runFunctionalTests = true;

	/**
	 * When running using this plugin ONLY, what jars should be inserted into the front of the classpath
	 * to ensure they get loaded first.
	 */
	@Parameter(property = "run.patchArtifacts")
	protected String patchArtifacts = null;


	protected List<String> artifactIdsToInsertAtStartOfClasspath = new ArrayList<String>();

	protected void parsePatchArtifacts() {
		if (patchArtifacts != null) {
			StringTokenizer st = new StringTokenizer(patchArtifacts, ",");

			while (st.hasMoreTokens()) {
				artifactIdsToInsertAtStartOfClasspath.add(st.nextToken());
			}
		}
	}

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

  protected String getFullGrailsPluginName() {
    String pluginName = getGrailsPluginFileName();

    return pluginName != null ? this.getBasedir() + File.separator + pluginName : null;
  }

  protected String getGrailsPluginFileName() {
    String artifactId = project.getArtifactId();

    String pluginName = GrailsNameUtils.getNameFromScript(project.getArtifactId()) + "GrailsPlugin.groovy";

    String name = this.getBasedir() + File.separator + pluginName;

    if (new File(name).exists()) {
      return pluginName;
    }

    if (artifactId.startsWith("grails-")) {
      artifactId = artifactId.substring("grails-".length());

      pluginName = GrailsNameUtils.getNameFromScript(artifactId) + "GrailsPlugin.groovy";

      name = this.getBasedir() + File.separator + pluginName;

      if (new File(name).exists()) {
        return pluginName;
      }
    }

    return null;
  }

  protected void syncAppVersion() {
    final Metadata metadata = Metadata.getInstance(new File(getBasedir(), "application.properties"));
    if (syncVersion(metadata))
      metadata.persist();

    String artifactId = project.getArtifactId();

    if (artifactId.startsWith("grails-"))
      artifactId = artifactId.substring("grails-".length());

    final String fName = getFullGrailsPluginName();
    if ( fName != null ) {
      File gpFile = new File( fName );

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
    return System.getProperty("os.name").toLowerCase().contains("windows");
  }

  // we are getting a situation where the same goal is running twice - two compiles, two test apps. This is a hack fix until the real cause is discovered.
  private static String lastTargetName;
  private static String lastArgs;
  private static String lastArtifactId;
  private static String lastGroupId;
  private static Set<Artifact> resolvedArtifacts;
  private static Set<Artifact> pluginArtifacts;
  private static List<File> pluginDirectories;
  private static URL[] classpath;
  private static String grailsHomePath;

  private void resolveClasspath() throws MojoExecutionException {
	  parsePatchArtifacts();

    getLog().info("Resolving dependencies" + (useTransitives?"":" - warning! we are not using transitive dependencies, only those directly in the pom.xml"));

    resolvedArtifacts = collectAllProjectArtifacts();


    /*
    * Remove any Grails plugins that may be in the resolved artifact set.  This is because we
    * do not need them on the classpath, as they will be handled later on by a separate call to
    * "install" them.
    */
    pluginArtifacts = removePluginArtifacts(resolvedArtifacts);

    pluginDirectories = new ArrayList<File>();

    for(Artifact artifact : pluginArtifacts)
      pluginDirectories.add(getPluginDirAndInstallIfNecessary(artifact));

    if (getLog().isInfoEnabled()) {
      for(File f : pluginDirectories) {
        getLog().info("plugin: " + f.getAbsolutePath());
      }
    }

    classpath = generateGrailsExecutionClasspath(resolvedArtifacts);

    System.gc();
  }

  private boolean alreadyLoaderClasspathForArtifact() {
    return project.getArtifactId().equalsIgnoreCase(lastArtifactId) && project.getGroupId().equalsIgnoreCase(lastGroupId);
  }

  // we only need to do these once as they don't change
  private void doOncePerArtifact() throws MojoExecutionException {
    lastArtifactId = project.getArtifactId();
    lastGroupId = project.getGroupId();

    configureMavenProxy();

    resolveClasspath();
//    printClasspath("main", Arrays.asList(classpath));

    grailsHomePath = (grailsHome != null) ? grailsHome.getAbsolutePath() : null;

    if (isWindows()) { // force console and interactive on to get around _GrailsRun.groovy windows bug where attaches to grailsConsole.reader.add...
      System.setProperty("grails.console.enable.terminal", "true");
      System.setProperty("grails.console.enable.interactive", "true");
    } else {
      if (System.getProperty("grails.console.enable.terminal") == null)
        System.setProperty("grails.console.enable.terminal", "true");
      if (System.getProperty("grails.console.enable.interactive") == null)
        System.setProperty("grails.console.enable.interactive", "true");
    }

    // override the servlet factory if it is specified
    if (this.servletServerFactory != null) {
      System.setProperty("grails.server.factory", this.servletServerFactory);
    }

    // see if we are using logback and not log4j
//    final String logbackFilename = this.getBasedir() + "/logback.xml";
//
//    if (new File(logbackFilename).exists()) {
//      getLog().info("Found logback configuration, setting logback.xml to " + logbackFilename);
//
//      System.setProperty("logback.configurationFile", logbackFilename);
//    }

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

//  void printClasspath(String name, List<URL> cp) {
//    StringBuilder sb = new StringBuilder();
//
//    for(URL c : cp)
//      sb.append(c.toExternalForm() + "\r\n");
//
//    getLog().info("name : " + sb.toString());
//  }


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

    if (!alreadyLoaderClasspathForArtifact())
      doOncePerArtifact();
    else if (targetName.equals("War"))
      resolveClasspath(); // we have to get rid of the test rubbish

    getLog().info("Grails target: " + targetName + " raw args:" + args + " (pom says Grails Version is " + grailsVersion + ")");

    InputStream currentIn = System.in;
    PrintStream currentOutput = System.out;

    try {
      RootLoader rootLoader = new RootLoader(addBinaryPluginWorkaround(classpath));

      // see if log4j is there and if so, initialize it
      try {
        Class cls = rootLoader.loadClass("org.springframework.util.Log4jConfigurer");
        invokeStaticMethod(cls, "initLogging", new Object[]{"classpath:grails-maven/log4j.properties"});
      } catch (Exception ex) {
        getLog().info("No log4j available, good!");
      }

      try {
        final DecentGrailsLauncher launcher = new DecentGrailsLauncher(rootLoader, grailsHomePath, basedir.getAbsolutePath());
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

        installGrailsPlugins(pluginDirectories, launcher, settingsField, rootLoader.loadClass("grails.util.AbstractBuildSettings"));

        // If the command is running in non-interactive mode, we
        // need to pass on the relevant argument.
        if (this.nonInteractive) {
          args = (args != null) ? "--non-interactive " + args : "--non-interactive ";
        }

        // consuming the standard output after execution via Maven.
        args = (args != null) ? "--plain-output " + args : "--plain-output";
        args = (args != null) ? "--stacktrace " + args : "--stacktrace";
        args = (args != null) ? "--verboseCompile " + args : "--verboseCompile";

        if (env == null)
          System.clearProperty("grails.env");
        else
          System.setProperty("grails.env", env);


        getLog().info("grails -Dgrails.env=" + (env==null?"dev":env) + " " + targetName.toLowerCase() + " " + args );
        int retval;

	      if ("true".equals(System.getProperty("print.grails.settings")) || "ideaprintprojectsettings".equalsIgnoreCase(targetName)) {
		      printIntellijIDEASettings(launcher, settingsField, pluginArtifacts);
	      } else {

		      if ("interactive".equals(targetName))
	          retval = launcher.launch("", "", env);
	        else
	          retval = launcher.launch(targetName, args, env);


	        if (retval != 0) {
	          throw new MojoExecutionException("Grails returned non-zero value: " + retval);
	        }

	      }
      } catch (final MojoExecutionException ex) {
        // Simply rethrow it.
        throw ex;
      } catch (final Exception ex) {
        getLog().error(ex);

        throw new MojoExecutionException("Unable to start Grails", ex);
      }

      rootLoader = null;
    } catch (MalformedURLException mfe) {
      throw new MojoExecutionException("Unable to start Grails", mfe);
    } finally {
      System.setIn(currentIn);
      System.setOut(currentOutput);
    }

    System.gc(); // try and help with memory issues
  }

  /**
   * Gets around an issue where a binary plugin's resource refers to a source plugins's resource and the binary plugin is subsequently
   * requested in a binary or source artifact.
   *
   * @param existing - the existing decoded classpath
   * @return - the new classpath with the extra directories in it where source plugins will be stored
   * @throws MalformedURLException
   */
  protected URL[] addBinaryPluginWorkaround(URL[] existing) throws MalformedURLException {

    List<URL> classpath = new ArrayList<URL>();

    classpath.add(new File(basedir, "target/plugin-build-classes").toURI().toURL());
    classpath.add(new File(basedir, "target/plugin-provided-classes").toURI().toURL());
    classpath.add(new File(basedir, "target/plugin-classes").toURI().toURL());
    classpath.add(new File(basedir, "target/resources").toURI().toURL());

    classpath.addAll(Arrays.asList(existing));

    URL[] workaround = new URL[classpath.size()];

    return classpath.toArray(workaround);
  }


  public static final String SETTINGS_START_MARKER = "---=== IDEA Grails build settings ===---";
  public static final String SETTINGS_END_MARKER = "---=== End IDEA Grails build settings ===---";

  private static Set GRAILS_PROPERTY_LIST = new HashSet(Arrays.asList(new String[]{"grails.work.dir", "grails.project.work.dir",
    "grails.project.target.dir", "grails.project.war.file", "grails.project.war.exploded.dir", "grails.project.class.dir",
    "grails.project.test.class.dir", "grails.project.resource.dir", "grails.project.source.dir", "grails.project.web.xml",
    "grails.project.plugins.dir", "grails.global.plugins.dir", "grails.project.test.reports.dir", "grails.project.test.source.dir"}));

  private void printIntellijIDEASettings(DecentGrailsLauncher launcher, Field settingsField, Set<Artifact> pluginArtifacts) {
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
  private Object invokeStaticMethod(Class target, String name, Object... args) {
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

  private String artifactToKey(Artifact artifact) {
    return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getClassifier();
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
    final Set<Artifact> resolvedArtifacts = new HashSet<Artifact>();

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

    Set<Artifact> uncheckedArtifacts = useTransitives ? resolveFromTree() : getResolvedArtifactsFromUnresolvedDependencies(project.getDependencies(), false);
    Map<String, Artifact> checklist = new HashMap<String, Artifact>();

    for( Artifact artifact : uncheckedArtifacts ) {
//      resolvedArtifacts.add(artifact);
      checklist.put(artifactToKey(artifact), artifact);
//      resolvedArtifacts.add(artifact);
    }

	  // major breaking change, no dependencies from plugin
	  /*
    for( Artifact artifact : getResolvedArtifactsFromUnresolvedDependencies(replaceVersion(filterGrailsDependencies(pluginProject.getDependencies())), true) ) {

//      resolvedArtifacts.add(artifact);

      String key = artifactToKey(artifact);
      Artifact existing = checklist.get(key);

      if (existing == null)
        checklist.put(key, artifact);
    }
    */

    resolvedArtifacts.addAll(checklist.values());

    return resolvedArtifacts;
  }

  private Set<Artifact> resolveFromTree() {
    final Set<Artifact> resolvedArtifacts = new HashSet<Artifact>();

    try {
      // we have to do this because Aether does not work.
      dependencyTreeBuilder.buildDependencyTree(project, localRepository, artifactFactory,
        artifactMetadataSource, artifactCollector).getRootNode().accept(new DependencyNodeVisitor() {
        @Override
        public boolean visit(DependencyNode dependencyNode) {
          Artifact artifact = dependencyNode.getArtifact();

          if (dependencyNode.getState() != DependencyNode.INCLUDED)
            return true;

          if (artifact.getArtifactId().equals(project.getArtifactId()) && artifact.getGroupId().equals(project.getGroupId()))
            return true;

          try {
            artifactResolver.resolve(artifact, remoteRepositories, localRepository);
          } catch (ArtifactResolutionException e) {
            throw new RuntimeException(e);
          } catch (ArtifactNotFoundException e) {
            throw new RuntimeException(e);
          }

          resolvedArtifacts.add(artifact);
          return true;
        }

        @Override
        public boolean endVisit(DependencyNode dependencyNode) {
          return true;
        }
      });
    } catch (DependencyTreeBuilderException e) {
      throw new RuntimeException(e);
    }

    return resolvedArtifacts;
  }

  private List<Dependency> replaceVersion(List<Dependency> dependencies) {
    if (grailsVersion != null) {
      for(Dependency d : dependencies) {
        if ("org.grails".equals(d.getGroupId()) && !grailsVersion.equals(d.getVersion()) && grailsVersion.charAt(0) == d.getVersion().charAt(0)) {
          d.setVersion( grailsVersion );
        }
      }
    }

    return dependencies;
  }


  private List<URL> generateExecutionClasspath(Set<Artifact> resolvedArtifacts, String... excludeGroups) throws MojoExecutionException {
    /*
    * Convert each resolved artifact into a URL/classpath element.
    */
    final ArrayList<URL> classpath = new ArrayList<URL>();

    final List<String> excludes = Arrays.asList(excludeGroups);

    try {

      for (Artifact resolvedArtifact : resolvedArtifacts) {
        if ( excludes.contains(resolvedArtifact.getGroupId())) continue;
        final File file = resolvedArtifact.getFile();
//        System.out.println("artifact " + resolvedArtifact.toString());
        if (file != null) {
	        if (artifactIdsToInsertAtStartOfClasspath.contains(resolvedArtifact.getArtifactId())) {
            getLog().info("adding at the start" + file.getAbsolutePath());
		        // a patch? grails is full of them, insert it at the start
		        classpath.add(0, file.toURI().toURL());
	        } else { // insert it at the end
		        classpath.add(file.toURI().toURL());
	        }

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

      if (System.getProperty("grails.debug.classpath") != null) {
        for (URL url : classpath) {
          getLog().info("classpath " + url.toString());
        }
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
   * @return The filtered list of dependencies.
   */
  private List<Dependency> filterGrailsDependencies(final List<Dependency> dependencies) {
    final List<Dependency> filteredDependencies = new ArrayList<Dependency>();
    for (final Dependency dependency : dependencies) {

	    if (dependency.getArtifactId().equals("grails-scripts") ||
		      dependency.getArtifactId().equals("grails-bootstrap") ||
		      dependency.getArtifactId().equals("grails-launcher")) {
        filteredDependencies.add(dependency);
      }
    }
    return filteredDependencies;
  }


  Set<Artifact> getResolvedArtifactsFromUnresolvedDependencies(List<Dependency> unresolvedDependencies, boolean resolveTransitively) throws MojoExecutionException {
    final Set<Artifact> resolvedArtifacts = new HashSet<Artifact>();
//    Artifact mojoArtifact = this.artifactFactory.createBuildArtifact(project.getGroupId(), project.getArtifactId(), project.getVersion(), "pom");

    /*
    * Resolve each artifact.  This will get all transitive artifacts AND eliminate conflicts.
    */
    final Set<Artifact> unresolvedArtifacts;

//    for (Dependency d : unresolvedDependencies)
//      System.out.println("dependency: " + d.toString());

    try {
      unresolvedArtifacts = MavenMetadataSource.createArtifacts(this.artifactFactory, unresolvedDependencies, null, null, null);
//      for (Artifact artifact : unresolvedArtifacts) {
//        System.out.println("unresolved " + artifact.toString());
//      }



      if (resolveTransitively) {
        ArtifactResolutionResult artifacts = artifactResolver.resolveTransitively(unresolvedArtifacts, project.getArtifact(),
            remoteRepositories, localRepository, artifactMetadataSource);
        resolvedArtifacts.addAll(artifacts.getArtifacts());
      } else {
        // resolve each artifact individually
        for( Artifact artifact : unresolvedArtifacts ) {
          artifactResolver.resolve(artifact, remoteRepositories, localRepository);

          resolvedArtifacts.add(artifact);
        }
      }



    } catch (Exception e) {
      throw new MojoExecutionException("Unable to complete configuring the build settings", e);
    }

    for (Artifact artifact : resolvedArtifacts) {
      System.out.println("matched " + artifact.toString());
    }

    return resolvedArtifacts;
  }

  private boolean logDependencies = false;

  /**
   * Configures the launcher for execution.
   *
   * @param launcher The {@code GrailsLauncher} instance to be configured.
   */
  @SuppressWarnings("unchecked")
  private Set<Artifact> configureBuildSettings(final DecentGrailsLauncher launcher, Set<Artifact> resolvedArtifacts, Field settingsField, Class clazz, String args) throws ProjectBuildingException, MojoExecutionException {
    final String targetDir = this.project.getBuild().getDirectory();
    launcher.setDependenciesExternallyConfigured(true);

    // allow plugins that are being developed with fake api implementations to include the test artifacts in the runtime
    if ((args != null && args.contains("--run-with-test-dependencies")) || runWithTestDependencies) {
	    getLog().warn("grails-maven: Running with test dependencies");
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

  private File getPluginTargetDirOverride(Artifact plugin) {
    String pluginLocationOverride = System.getProperty(plugin.getGroupId() + ":" + plugin.getArtifactId());

    File targetDir = null;

    if (pluginLocationOverride != null && pluginLocationOverride.length() > 0) {
      targetDir = new File(pluginLocationOverride);
      if (!targetDir.exists()) {
        getLog().error(String.format("Specified directory (%s) for plugin %s:%s:%s could not be found", pluginLocationOverride, plugin.getGroupId(), plugin.getArtifactId(), plugin.getVersion()));
        targetDir = null;
      }
    }
    return targetDir;
  }

  private File getPluginTargetDirCentral(Artifact plugin) {
      return new File(this.centralPluginInstallDir, getPluginName(plugin) + "-" + plugin.getVersion());
  }

  private File getPluginTargetDir(Artifact plugin) {
    File targetDir = getPluginTargetDirOverride(plugin);

    if (targetDir == null) {
      // The directory the plugin will be unzipped to.
      targetDir = getPluginTargetDirCentral(plugin);
    }

    return targetDir;
  }

  private File getPluginDirAndInstallIfNecessary(final Artifact plugin) throws MojoExecutionException {
    boolean targetDirOverridden = true;
    File targetDir = getPluginTargetDirOverride(plugin);
    if (targetDir == null){
        targetDirOverridden = false;
        targetDir = getPluginTargetDirCentral(plugin);
    }

    String pluginName = getPluginName(plugin);
    final String pluginVersion = plugin.getVersion();
    boolean snapshot = pluginVersion.endsWith("-SNAPSHOT");

    if (snapshot && plugin.getFile().getAbsolutePath().endsWith("target" + File.separator + "classes")) { // multi module build

      targetDir = plugin.getFile().getParentFile().getParentFile();
      getLog().info(String.format("Plugin %s:%s is coming from a multi-module dependency (%s)", pluginName, pluginVersion, targetDir.getAbsolutePath()));

    } else if ( (!snapshot && !targetDir.exists()) || (snapshot && !targetDirOverridden)) {
      // Unpack the plugin if it hasn't already been or if its a SNAPSHOT and not overridden by -Dflag

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
      try {
        unzipper.extract();
      } catch (ArchiverException e) {
        throw new MojoExecutionException("Unable to extract zip", e);
      }
	    try {
		    File inputPom = new File(plugin.getFile().getParentFile(), pluginName + "-" + pluginVersion + ".pom");
		    File outputPom = new File(targetDir, "pom.xml");
		    getLog().info(String.format("copying %s to %s", inputPom.getAbsolutePath(), outputPom.getAbsolutePath()));
		    FileReader fr = new FileReader(inputPom);
		    FileWriter fw = new FileWriter(outputPom);
		    IOUtils.copy(fr, fw);
		    fw.flush();
		    fw.close();
		    fr.close();
	    } catch (IOException e) {
		    throw new MojoExecutionException("Unable to copy pom.xml file");
	    }
    } else {
      getLog().info(String.format("Plugin %s:%s already installed (%s)", pluginName, pluginVersion, targetDir.getAbsolutePath()));
    }

    return targetDir;
  }


  /**
   * Installs a Grails plugin into the current project if it isn't
   * already installed. It works by simply unpacking the plugin
   * artifact (a ZIP file) into the appropriate location and adding
   * the plugin to the application's metadata.
   *
   * @param plugins   The plugin artifact to install.
   * @param launcher The launcher instance that contains information about
   *                 the various project directories. In particular, this is where the
   *                 method gets the location of the project's "plugins" directory
   *                 from.
   * @return <code>true</code> if the plugin is installed and the
   *         metadata updated, otherwise <code>false</code>.
   * @throws IOException
   * @throws ArchiverException
   */
  private boolean installGrailsPlugins(
    List<File> plugins,
    final DecentGrailsLauncher launcher,
    Field settingsField,
    Class clazz) throws MojoExecutionException {

    Object settings = null;
    try {
      settings = settingsField.get(launcher);

      Method m = clazz.getDeclaredMethod("addPluginDirectory", new Class[]{File.class, boolean.class});

      for(File targetDir : plugins)
        m.invoke(settings, targetDir, true);

    } catch (Exception e) {
      throw new MojoExecutionException("Unable to install plugins", e);
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

    if (logDependencies)
      getLog().info(sb.toString());

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
        if (dep.getType() != null && (dep.getType().equals("grails-plugin") || dep.getType().equals("zip") || (dep.getType().equals("grails-plugin2") && "plugin".equals(dep.getClassifier())) )) {
          pluginArtifacts.add(dep);
//          System.out.println("removing " + dep.toString());
          iter.remove();
        }
      }
    }

    return pluginArtifacts;
  }
}
