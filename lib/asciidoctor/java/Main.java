// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.AttributesBuilder;
import org.asciidoctor.Options;
import org.asciidoctor.OptionsBuilder;
import org.asciidoctor.internal.JRubyAsciidoctor;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

public class Main {

  private static final int BUFSIZ = 4096;
  private static final String DOCTYPE = "article";
  private static final String ERUBY = "erb";

  @Option(name = "-b", usage = "set output format backend")
  private String backend = "html5";

  @Option(name = "-z", usage = "output zip file")
  private String zipFile;

  @Option(name = "--in-ext", usage = "extension for input files")
  private String inExt = ".txt";

  @Option(name = "--out-ext", usage = "extension for output files")
  private String outExt = ".html";

  @Option(name = "-a", usage =
      "a list of attributes, in the form key or key=value pair")
  private List<String> attributes = new ArrayList<String>();

  @Argument(usage = "input files")
  private List<String> inputFiles = new ArrayList<String>();

  private String mapInFileToOutFile(String inFile) {
    String basename = new File(inFile).getName();
    if (basename.endsWith(inExt)) {
      basename = basename.substring(0, basename.length() - inExt.length());
    } else {
      // Strip out the last extension
      int pos = basename.lastIndexOf('.');
      if (pos > 0) {
        basename = basename.substring(0, pos);
      }
    }
    return basename + outExt;
  }

  private Options createOptions(File tmpFile) {
    OptionsBuilder optionsBuilder = OptionsBuilder.options();

    optionsBuilder.backend(backend).docType(DOCTYPE).eruby(ERUBY);
    // XXX(fishywang): ideally we should just output to a string and add the
    // content into zip. But asciidoctor will actually ignore all attributes if
    // not output to a file. So we *have* to output to a file then read the
    // content of the file into zip.
    optionsBuilder.toFile(tmpFile);

    AttributesBuilder attributesBuilder = AttributesBuilder.attributes();
    attributesBuilder.attributes(getAttributes());
    optionsBuilder.attributes(attributesBuilder.get());

    return optionsBuilder.get();
  }

  private Map<String, Object> getAttributes() {
    Map<String, Object> attributeValues = new HashMap<String, Object>();

    for (String attribute : attributes) {
      int equalsIndex = attribute.indexOf('=');
      if(equalsIndex > -1) {
        String name = attribute.substring(0, equalsIndex);
        String value = attribute.substring(equalsIndex + 1, attribute.length());

        attributeValues.put(name, value);
      } else {
        attributeValues.put(attribute, "");
      }
    }

    return attributeValues;
  }

  private void invoke(String... parameters) throws IOException {
    CmdLineParser parser = new CmdLineParser(this);
    try {
      parser.parseArgument(parameters);
      if (inputFiles.isEmpty()) {
        throw new CmdLineException(parser,
            "asciidoctor: FAILED: input file missing");
      }
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.exit(1);
      return;
    }

    ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipFile));
    byte[] buf = new byte[BUFSIZ];
    for (String inputFile : inputFiles) {
      File tmp = File.createTempFile("doc", ".html");
      Options options = createOptions(tmp);
      renderInput(options, inputFile);

      FileInputStream input = new FileInputStream(tmp);
      int len;
      zip.putNextEntry(new ZipEntry(mapInFileToOutFile(inputFile)));
      while ((len = input.read(buf)) > 0) {
        zip.write(buf, 0, len);
      }
      input.close();
      tmp.delete();
      zip.closeEntry();
    }
    zip.close();
  }

  private void renderInput(Options options, String inputFile) {
    Asciidoctor asciidoctor = JRubyAsciidoctor.create();
    asciidoctor.renderFile(new File(inputFile), options);
  }

  public static void main(String[] args) {
    try {
      new Main().invoke(args);
    } catch (IOException e) {
      System.err.println(e.getMessage());
      System.exit(1);
    }
  }
}
