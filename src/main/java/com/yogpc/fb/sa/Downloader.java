package com.yogpc.fb.sa;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Downloader implements Runnable {
  private static File tryDownload(final URL url, final String base, final File tout,
      final String ext) throws NoSuchAlgorithmException, IOException {
    final byte[] buf = new byte[8192];
    int nread;
    File out = new File(Constants.DATA_DIR, "cache" + File.separator + base);
    final File etag = new File(out, "etag");
    final File lm = new File(out, "lm");
    final File sum = new File(out, "sum");
    if (tout != null)
      out = tout;
    else
      out = new File(out, "file." + ext);
    if (!out.exists()) {
      etag.delete();
      lm.delete();
      sum.delete();
      out.delete();
    } else if (sum.exists()) {
      final MessageDigest md = MessageDigest.getInstance("SHA-512");
      final InputStream is = new FileInputStream(out);
      while ((nread = is.read(buf)) > -1)
        md.update(buf, 0, nread);
      is.close();
      if (!Arrays.equals(md.digest(), Utils.fileToByteArray(sum))) {
        etag.delete();
        lm.delete();
        sum.delete();
        out.delete();
      }
    }

    try {
      final HttpURLConnection uc = (HttpURLConnection) url.openConnection();
      uc.setInstanceFollowRedirects(true);
      if (etag.exists())
        uc.setRequestProperty("If-None-Match", Utils.fileToString(etag, Utils.UTF_8));
      if (lm.exists())
        uc.setRequestProperty("If-Modified-Since", Utils.fileToString(lm, Utils.UTF_8));
      uc.connect();
      if (uc.getResponseCode() == HttpURLConnection.HTTP_NOT_MODIFIED)
        return out;
      if (uc.getResponseCode() != HttpURLConnection.HTTP_OK)
        return null;
      out.getParentFile().mkdirs();
      sum.getParentFile().mkdirs();
      if (uc.getHeaderField("ETag") != null)
        Utils.stringToFile(uc.getHeaderField("ETag"), etag, Utils.UTF_8);
      if (uc.getHeaderField("Last-Modified") != null)
        Utils.stringToFile(uc.getHeaderField("Last-Modified"), lm, Utils.UTF_8);
      final MessageDigest md = MessageDigest.getInstance("SHA-512");
      final InputStream is = uc.getInputStream();
      final OutputStream os = new FileOutputStream(out);
      while ((nread = is.read(buf)) > -1) {
        os.write(buf, 0, nread);
        md.update(buf, 0, nread);
      }
      os.close();
      is.close();
      uc.disconnect();
      Utils.byteArrayToFile(md.digest(), sum);
    } catch (final MalformedURLException e) {
      return null;
    } catch (final IOException e) {
      return null;
    }
    return out;
  }

  private static final String[] REPOS = {"http://repo.maven.apache.org/maven2/",
      Constants.FORGE_BASE + "maven/", "https://libraries.minecraft.net/",
      "http://chickenbones.net/maven/", "http://maven.ic2.player.to/"};

  private static File downloadMaven(final String group, final String artifact,
      final String version, final String sub) throws IOException, NoSuchAlgorithmException {
    final StringBuilder sb = new StringBuilder();
    sb.append(group.replace(".", "/")).append("/");
    sb.append(artifact).append("/").append(version);
    String cp = sb.toString();
    sb.append("/").append(artifact).append('-').append(version);
    if (sub != null) {
      sb.append('-').append(sub);
      cp = cp + "-" + sub;
    }
    sb.append(".jar");
    File f = null;
    final File lp =
        new File(Constants.MINECRAFT_LIBRARIES, sb.toString().replace("/", File.separator));
    for (final String base : REPOS) {
      f = tryDownload(new URL(base + sb.toString()), cp, lp, "jar");
      if (f != null)
        break;
    }
    if (f == null && lp.exists()) {
      f = lp;
      System.out.println(">> Use local library cache " + group + ":" + artifact + ":" + version
          + ":" + sub);
    }
    return f;
  }

  private final String name;
  private final String[] maven;
  private final URL url;
  private final String ext;
  private final Thread t;
  private File ret;

  public File getFile() {
    return this.ret;
  }

  Downloader(final String g, final String a, final String v, final String s) {
    this.name = null;
    this.maven = new String[] {g, a, v, s};
    this.url = null;
    this.ret = null;
    this.ext = null;
    this.t = new Thread(this);
    this.t.start();
  }

  public Downloader(final String n, final URL u, final String e) {
    this.name = n;
    this.maven = null;
    this.url = u;
    this.ret = null;
    this.ext = e;
    this.t = new Thread(this);
    this.t.start();
  }

  public Downloader(final String n, final URL u, final File f) {
    this.name = n;
    this.maven = null;
    this.url = u;
    this.ret = f;
    this.ext = null;
    this.t = new Thread(this);
    this.t.start();
  }

  public void join() throws InterruptedException {
    this.t.join();
  }

  @Override
  public void run() {
    try {
      if (this.maven != null)
        this.ret = downloadMaven(this.maven[0], this.maven[1], this.maven[2], this.maven[3]);
      else if (this.url != null)
        this.ret = tryDownload(this.url, this.name, this.ret, this.ext);
    } catch (final Exception e) {
      this.ret = null;
    }
  }

  public static final Pattern lib_nam = Pattern.compile("([^:]+):([^:]+):([^:]+)(?::([^:]+))?");

  public static List<File>[] resolveDepends(final List<?>... l) throws InterruptedException,
      MalformedURLException {
    @SuppressWarnings("unchecked")
    final List<Downloader>[] p = new List[l.length];
    final List<Downloader> f = new ArrayList<Downloader>();
    for (int i = 0; i < l.length; i++) {
      p[i] = new ArrayList<Downloader>();
      if (l[i] != null)
        for (final Object s : l[i]) {
          final String ss = (String) s;
          final Matcher m = lib_nam.matcher(ss);
          if (!ss.startsWith("http://") && !ss.startsWith("https://") && m.matches()) {
            final String g = m.group(1), a = m.group(2), v = m.group(3);
            p[i].add(new Downloader(g, a, v, m.group(4)));
            f.add(new Downloader(g, a, v, "sources"));
            f.add(new Downloader(g, a, v, "javadoc"));
            f.add(new Downloader(g, a, v, "natives-" + Constants.OS));
          } else {
            final String n =
                ss.replace(":", "%3A").replace("//", "/").replace('/', File.separatorChar);
            p[i].add(new Downloader(n, new URL(ss), "jar"));
          }
        }
    }
    for (final Downloader d : f)
      d.join();
    @SuppressWarnings("unchecked")
    final List<File>[] ret = new List[l.length];
    for (int i = 0; i < l.length; i++) {
      ret[i] = new ArrayList<File>();
      if (p[i] != null)
        for (final Downloader d : p[i]) {
          d.join();
          if (d.ret != null)
            ret[i].add(d.ret);
        }
    }
    return ret;
  }
}
