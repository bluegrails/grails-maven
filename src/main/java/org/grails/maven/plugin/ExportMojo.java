package org.grails.maven.plugin;

import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pulls all of the source into one file for code coverage purposes
 *
 * @author Richard Vowles
 * @version $Id$
 * @description Exports all of the source in a single zip
 * @goal export-src
 * @requiresProject true
 * @requiresDependencyResolution compile
 * @since 2.8
 */
public class ExportMojo extends AbstractMojo {

  /**
   * POM
   *
   * @parameter expression="${project}"
   * @readonly
   * @required
   */
  protected MavenProject project;

  /**
   * The directory where is launched the mvn command.
   *
   * @parameter default-value="${basedir}"
   * @required
   */
  protected File basedir;


  protected String excludes;


  protected String includes;

  private static final String EXPORTED_SOURCE_DIR = "exported-source";

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    List<Artifact> artifacts = project.getCompileArtifacts();

    File outputDir = new File(basedir, "target/" + EXPORTED_SOURCE_DIR);

    outputDir.mkdirs();

    for(Artifact artifact : artifacts) {
      if ("grails-plugin".equals(artifact.getType())) {
        try {
          getLog().info(String.format("exporting: %s:%s", artifact.getGroupId(), artifact.getArtifactId()));
          extractZipArtifact(artifact.getFile(), outputDir);
        } catch (IOException iex) {
          String msg = String.format("Artifact %s:%s failed to export (%s)", artifact.getGroupId(), artifact.getArtifactId(), artifact.getFile().getAbsolutePath());
          getLog().error(msg, iex);
          throw new MojoFailureException(msg);
        }
      }
    }

    try {
      extractDirArtifact(basedir, outputDir, "");
    } catch (IOException e) {
      getLog().error(String.format("Unable to export %s into %s", basedir.getAbsolutePath(), outputDir.getAbsolutePath()));
      throw new MojoFailureException("Unable to export self into sources directory");
    }
  }

  private List<String> prefixes = Arrays.asList("grails-app/conf/spring/", "grails-app'conf/hibernate/", "grails-app/conf/", "grails-app/controllers/", "grails-app/domain/", "grails-app/taglib/",
      "grails-app/services/", "grails-app/utils/", "src/groovy/", "src/java/");

  private void extractDirArtifact(File baseDir, File outputDir, String offset) throws IOException {
    for( File file : baseDir.listFiles()) {
      if (file.isDirectory()) {
        if (file.getName().startsWith(".")) continue;
        extractDirArtifact(file, outputDir, offset + file.getName() + "/");
      } else if ( file.getName().endsWith("Plugin.groovy")) {
        IOUtils.copy(new FileReader(file), new FileWriter(new File(outputDir, file.getName())));
      } else {
        String prefix = prefixMatch(offset + file.getName());

        if (prefix != null) {
          String fullName = (offset + file.getName()).substring(prefix.length());

          getLog().info(String.format("prefix is %s and entry is %s and final is [%s]", prefix, file.getName(), fullName));

          File outputFile = new File(outputDir, fullName);

          ensureParentDirectoriesExist(outputDir, outputFile);

          IOUtils.copy(new FileReader(file), new FileWriter(outputFile));
        }
      }
    }
  }

  private void extractZipArtifact(File file, File outputDir) throws IOException {
    ZipFile zipFile = new ZipFile(file);

    Enumeration<? extends ZipEntry> entries = zipFile.entries();

    while(entries.hasMoreElements()) {
      ZipEntry entry = entries.nextElement();

      if (entry.isDirectory()) continue;

      if (entry.getName().endsWith("Plugin.groovy")) {
        IOUtils.copy(zipFile.getInputStream(entry), new FileWriter(new File(outputDir, entry.getName())));
      } else {
        String prefix = prefixMatch(entry.getName());

        if (prefix != null) {
          getLog().info(String.format("prefix is %s and entry is %s and final is [%s]", prefix, entry.getName(), entry.getName().substring(prefix.length())));

          File outputFile = new File(outputDir, entry.getName().substring(prefix.length()));

          ensureParentDirectoriesExist(outputDir, outputFile);

          IOUtils.copy(zipFile.getInputStream(entry), new FileWriter(outputFile));
        }
      }
    }
  }

  private void ensureParentDirectoriesExist(File outputDir, File outputFile) {
    if (!outputFile.getParentFile().equals(outputDir))
      outputFile.getParentFile().mkdirs();
  }

  private String prefixMatch(String name) {
    for(String prefix: prefixes) {
      if (name.startsWith(prefix)) {
        return prefix;
      }
    }

    return null;
  }
}
