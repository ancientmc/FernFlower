/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package org.jetbrains.java.decompiler.main.collectors;

import org.jetbrains.java.decompiler.main.ClassesProcessor;
import org.jetbrains.java.decompiler.main.ClassesProcessor.ClassNode;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.TextBuffer;
import org.jetbrains.java.decompiler.struct.StructContext;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;


public class ImportCollector {

  private static final String JAVA_LANG_PACKAGE = "java.lang";

  private final Map<String, String> mapSimpleNames = new HashMap<String, String>();
  private final Set<String> setNotImportedNames = new HashSet<String>();
  private String currentPackageSlash = "";
  private String currentPackagePoint = "";

  public ImportCollector(ClassNode root) {

    String clname = root.classStruct.qualifiedName;
    int index = clname.lastIndexOf("/");
    if (index >= 0) {
      currentPackageSlash = clname.substring(0, index);
      currentPackagePoint = currentPackageSlash.replace('/', '.');
      currentPackageSlash += "/";
    }
  }

  public String getShortName(String fullname) {
    return getShortName(fullname, true);
  }

  public String getShortName(String fullname, boolean imported) {

    ClassesProcessor clproc = DecompilerContext.getClassProcessor();
    ClassNode node = clproc.getMapRootClasses().get(fullname.replace('.', '/'));

    String retname = null;

    if (node != null && node.classStruct.isOwn()) {

      retname = node.simpleName;

      while (node.parent != null && node.type == ClassNode.CLASS_MEMBER) {
        retname = node.parent.simpleName + "." + retname;
        node = node.parent;
      }

      if (node.type == ClassNode.CLASS_ROOT) {
        fullname = node.classStruct.qualifiedName;
        fullname = fullname.replace('/', '.');
      }
      else {
        return retname;
      }
    }
    else {
      fullname = fullname.replace('$', '.');
    }

    String nshort = fullname;
    String npackage = "";

    int lastpoint = fullname.lastIndexOf(".");

    if (lastpoint >= 0) {
      nshort = fullname.substring(lastpoint + 1);
      npackage = fullname.substring(0, lastpoint);
    }

    StructContext context = DecompilerContext.getStructContext();

    // check for another class which could 'shadow' this one. Two cases:
    // 1) class with the same short name in the current package
    // 2) class with the same short name in the default package
    boolean existsDefaultClass = (context.getClass(currentPackageSlash + nshort) != null
                                  && !npackage.equals(currentPackagePoint)) // current package
                                 || (context.getClass(nshort) != null 
                                  && !currentPackagePoint.isEmpty());  // default package

    if (existsDefaultClass ||
        (mapSimpleNames.containsKey(nshort) && !npackage.equals(mapSimpleNames.get(nshort)))) {
      return fullname;
    }
    else if (!mapSimpleNames.containsKey(nshort)) {
      mapSimpleNames.put(nshort, npackage);

      if (!imported) {
        setNotImportedNames.add(nshort);
      }
    }

    return retname == null ? nshort : retname;
  }

  public int writeImports(TextBuffer buffer) {
    int importlines_written = 0;

    List<String> imports = packImports();

    for (String s : imports) {
      buffer.append("import ");
      buffer.append(s);
      buffer.append(";");
      buffer.appendLineSeparator();

      importlines_written++;
    }

    return importlines_written;
  }

  private List<String> packImports() {
    return mapSimpleNames.entrySet().stream()
            .filter(ent ->
                    // exclude the current class or one of the nested ones
                    // empty, java.lang and the current packages
                    !setNotImportedNames.contains(ent.getKey()) &&
                    !ent.getValue().contains(ent.getKey()) &&
                    !JAVA_LANG_PACKAGE.equals(ent.getValue()) &&
                    !ent.getValue().equals(currentPackagePoint)
            )
            .sorted(Map.Entry.<String, String>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
            .map(ent -> ent.getValue() + "." + ent.getKey())
            .collect(Collectors.toList());
  }
}
