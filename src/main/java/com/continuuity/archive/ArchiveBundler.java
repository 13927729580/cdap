/*
 * Copyright 2012-2013 Continuuity,Inc. All Rights Reserved.
 */

package com.continuuity.archive;

import com.continuuity.common.utils.ImmutablePair;
import com.continuuity.filesystem.Location;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.io.ByteStreams;

import java.io.IOException;
import java.io.InputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * ArchiveBundler is a utlity class that allows to clone a JAR file with modifications
 * to the manifest file and also adds few additional files to the cloned JAR.
 */
public final class ArchiveBundler {

  /**
   * Default location of additional files in cloned archive.
   */
  private static final String DEFAULT_LOC = "META-INF/specification/";

  /**
   * Main archive that needs to be cloned.
   */
  private final Location archive;

  /**
   * Base location where the files in cloned archive will be stored.
   */
  private final String jarEntryPrefix;


  /**
   * Constructor with archive to be cloned.
   *
   * @param archive to be cloned.
   */
  public ArchiveBundler(Location archive) {
    this(archive, DEFAULT_LOC);
  }

  /**
   * Constructor that takes in the archive to be cloned
   *
   * @param archive        to be cloned
   * @param jarEntryPrefix within cloned archive for additional files.
   */
  public ArchiveBundler(Location archive, String jarEntryPrefix) {
    Preconditions.checkNotNull(jarEntryPrefix, "Entry prefix of additional files in JAR is null");
    this.archive = archive;
    if(jarEntryPrefix.charAt(jarEntryPrefix.length() - 1) != '/') {
      jarEntryPrefix += "/";
    }
    this.jarEntryPrefix = jarEntryPrefix;
  }

  /**
   * Clones the input <code>archive</code> file with MANIFEST file and also adds addition
   * Files to the cloned archive.
   *
   * @param output   Cloned output archive
   * @param manifest New manifest file to be added to the cloned archive
   * @param files    Additional files to be added to cloned archive. Pairs are: resource_name_in_manifest,file_to_add
   * @throws IOException thrown when issue with handling of files.
   */
  public void clone(Location output, Manifest manifest, Iterable<ImmutablePair<String, Location>> files)
    throws IOException {
    clone(output, manifest, files, Predicates.<JarEntry>alwaysFalse());
  }

  /**
   * Clones the input <code>archive</code> file with MANIFEST file and also adds addition
   * Files to the cloned archive.
   *
   * @param output       Cloned output archive
   * @param manifest     New manifest file to be added to the cloned archive
   * @param files        Additional files to be added to cloned archive.
   *                     Pairs are: resource_name_in_manifest,file_to_add
   * @param ignoreFilter Filter applied on ZipEntry, if true file is ignored, otherwise will be accepted.
   * @throws IOException thrown when issue with handling of files.
   */
  public void clone(Location output, Manifest manifest,
                    Iterable<ImmutablePair<String, Location>> files,
                    Predicate<JarEntry> ignoreFilter) throws IOException {
    Preconditions.checkNotNull(manifest, "Null manifest");
    Preconditions.checkNotNull(files);

    // Create a input stream based on the original archive file.
    JarInputStream zin = new JarInputStream(archive.getInputStream());

    // Create a new output JAR file with new MANIFEST.
    JarOutputStream zout = new JarOutputStream(output.getOutputStream(), manifest);

    try {
      // Iterates through the input zip entry and make sure, the new files
      // being added are not already present. If not, they are added to the
      // output zip.
      JarEntry entry = zin.getNextJarEntry();
      while(entry != null) {
        // Invoke the predicate to see if the entry needs to be filtered.
        // If the ignoreFilter returns true, then it needs to be filtered; false keep it.
        if(ignoreFilter.apply(entry)) {
          entry = zin.getNextJarEntry();
          continue;
        }

        String name = entry.getName();
        boolean absenceInJar = true;

        // adding entries missing in jar
        for(ImmutablePair<String, Location> toAdd : files) {
          String entryToAdd = toAdd.getFirst();
          if(entryToAdd.equals(name)) {
            absenceInJar = false;
            break;
          }
        }

        if(absenceInJar) {
          zout.putNextEntry(new JarEntry(entry));
          ByteStreams.copy(zin, zout);
        }
        entry = zin.getNextJarEntry();
      }
    } finally {
      // Close the stream
      zin.close();
    }

    try {
      // Add the new files.
      for(ImmutablePair<String, Location> toAdd : files) {
        Location file = toAdd.getSecond();
        String entryName = toAdd.getFirst();
        InputStream in = file.getInputStream();
        try {
          zout.putNextEntry(new JarEntry(jarEntryPrefix + entryName));
          ByteStreams.copy(in, zout);
          zout.closeEntry();
        } finally {
          in.close();
        }
      }
    } finally {
      zout.close();
    }
  }
}
