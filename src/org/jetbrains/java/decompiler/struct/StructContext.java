/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.java.decompiler.struct;

import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericMain;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericMethodDescriptor;
import org.jetbrains.java.decompiler.struct.lazy.LazyLoader;
import org.jetbrains.java.decompiler.util.DataInputFullStream;
import org.jetbrains.java.decompiler.util.InterpreterUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class StructContext {

  private final IResultSaver saver;
  private final IDecompiledData decompiledData;
  private final LazyLoader loader;
  private final Map<String, ContextUnit> units = new HashMap<String, ContextUnit>();
  private final Map<String, StructClass> classes = new HashMap<String, StructClass>();
  private final Map<String, List<String>> abstractNames = new HashMap<>();

  public StructContext(IResultSaver saver, IDecompiledData decompiledData, LazyLoader loader) {
    this.saver = saver;
    this.decompiledData = decompiledData;
    this.loader = loader;

    ContextUnit defaultUnit = new ContextUnit(ContextUnit.TYPE_FOLDER, null, "", true, saver, decompiledData);
    units.put("", defaultUnit);
  }

  public StructClass getClass(String name) {
    return classes.get(name);
  }

  public void reloadContext() throws IOException {
    for (ContextUnit unit : units.values()) {
      for (StructClass cl : unit.getClasses()) {
        classes.remove(cl.qualifiedName);
      }

      unit.reload(loader);

      // adjust global class collection
      for (StructClass cl : unit.getClasses()) {
        classes.put(cl.qualifiedName, cl);
      }
    }
  }

  public void saveContext() {
    for (ContextUnit unit : units.values()) {
      if (unit.isOwn()) {
        unit.save();
      }
    }
  }

  public void addSpace(File file, boolean isOwn) {
    addSpace("", file, isOwn, 0);
  }

  private void addSpace(String path, File file, boolean isOwn, int level) {
    if (file.isDirectory()) {
      if (level == 1) path += file.getName();
      else if (level > 1) path += "/" + file.getName();

      File[] files = file.listFiles();
      if (files != null) {
        for (int i = files.length - 1; i >= 0; i--) {
          addSpace(path, files[i], isOwn, level + 1);
        }
      }
    }
    else {
      String filename = file.getName();

      boolean isArchive = false;
      try {
        if (filename.endsWith(".jar")) {
          isArchive = true;
          addArchive(path, file, ContextUnit.TYPE_JAR, isOwn);
        }
        else if (filename.endsWith(".zip")) {
          isArchive = true;
          addArchive(path, file, ContextUnit.TYPE_ZIP, isOwn);
        }
      }
      catch (IOException ex) {
        String message = "Corrupted archive file: " + file;
        DecompilerContext.getLogger().writeMessage(message, ex);
        throw new RuntimeException(ex);
      }
      if (isArchive) {
        return;
      }

      ContextUnit unit = units.get(path);
      if (unit == null) {
        unit = new ContextUnit(ContextUnit.TYPE_FOLDER, null, path, isOwn, saver, decompiledData);
        units.put(path, unit);
      }

      if (filename.endsWith(".class")) {
        try {
          DataInputFullStream in = loader.getClassStream(file.getAbsolutePath(), null);
          try {
            StructClass cl = new StructClass(in, isOwn, loader);
            classes.put(cl.qualifiedName, cl);
            unit.addClass(cl, filename);
            loader.addClassLink(cl.qualifiedName, new LazyLoader.Link(LazyLoader.Link.CLASS, file.getAbsolutePath(), null));
          }
          finally {
            in.close();
          }
        }
        catch (IOException ex) {
          String message = "Corrupted class file: " + file;
          DecompilerContext.getLogger().writeMessage(message, ex);
          throw new RuntimeException(ex);
        }
      }
      else {
        unit.addOtherEntry(file.getAbsolutePath(), filename);
      }
    }
  }

  private void addArchive(String path, File file, int type, boolean isOwn) throws IOException {
    @SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
    ZipFile archive = type == ContextUnit.TYPE_JAR ? new JarFile(file) : new ZipFile(file);

    try {
      Enumeration<? extends ZipEntry> entries = archive.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();

        ContextUnit unit = units.get(path + "/" + file.getName());
        if (unit == null) {
          unit = new ContextUnit(type, path, file.getName(), isOwn, saver, decompiledData);
          if (type == ContextUnit.TYPE_JAR) {
            unit.setManifest(((JarFile)archive).getManifest());
          }
          units.put(path + "/" + file.getName(), unit);
        }

        String name = entry.getName();
        if (!entry.isDirectory()) {
          if (name.endsWith(".class")) {
            byte[] bytes = InterpreterUtil.getBytes(archive, entry);
            StructClass cl = new StructClass(bytes, isOwn, loader);
            classes.put(cl.qualifiedName, cl);
            unit.addClass(cl, name);
            loader.addClassLink(cl.qualifiedName, new LazyLoader.Link(LazyLoader.Link.ENTRY, file.getAbsolutePath(), name));
          }
          else {
            unit.addOtherEntry(file.getAbsolutePath(), name);
          }
        }
        else {
          unit.addDirEntry(name);
        }
      }
    }
    finally {
      archive.close();
    }
  }

  public Map<String, StructClass> getClasses() {
    return classes;
  }

  public void loadAbstractMetadata(String string) {
    for (String line : string.split("\\n")) {
      String[] pts = line.split(" ");
      if (pts.length < 4) //class method desc [args...]
        continue;
      GenericMethodDescriptor desc = GenericMain.parseMethodSignature(pts[2]);
      List<String> params = new ArrayList<>();
      for (int x = 0; x < pts.length - 3; x++) {
        for (int y = 0; y < desc.params.get(x).stackSize; y++)
          params.add(pts[x+3]);
      }
      this.abstractNames.put(pts[0] + ' ' + pts[1] + ' ' + pts[2], params);
    }
  }

  public String renameAbstractParameter(String className, String methodName, String descriptor, int index, String _default) {
    List<String> params = this.abstractNames.get(className + ' ' + methodName + ' ' + descriptor);
    return params != null && index < params.size() ? params.get(index) : _default;
  }

  public void addData(String path, String cls, byte[] data, boolean isOwn) throws IOException {
        ContextUnit unit = units.get(path);
        if (unit == null) {
          unit = new ContextUnit(ContextUnit.TYPE_FOLDER, path, cls, isOwn, saver, decompiledData);
          units.put(path, unit);
        }

        StructClass cl = new StructClass(new DataInputFullStream(data), isOwn, loader);
        classes.put(cl.qualifiedName, cl);
        unit.addClass(cl, cls);
        loader.addClassLink(cl.qualifiedName, new LazyLoader.Link(path, cls, data));
  }
}
