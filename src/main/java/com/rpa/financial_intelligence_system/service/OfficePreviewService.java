package com.rpa.financial_intelligence_system.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class OfficePreviewService {
 private static final Set<String> SUPPORTED=Set.of("doc","docx","xls","xlsx","xlsm","ppt","pptx");
 private final StorageService storage;
 private final String command;
 private final Duration timeout;
 private final Path cacheRoot;
 private final Map<Long,Object> locks=new ConcurrentHashMap<>();

 public OfficePreviewService(StorageService storage,
   @Value("${app.preview.libreoffice-command:soffice}")String command,
   @Value("${app.preview.timeout-seconds:60}")long timeoutSeconds)throws Exception{
  this.storage=storage;this.command=command;this.timeout=Duration.ofSeconds(Math.max(10,timeoutSeconds));
  this.cacheRoot=Path.of(System.getProperty("java.io.tmpdir"),"finsight-office-preview").toAbsolutePath().normalize();
  Files.createDirectories(cacheRoot);
 }

 public ByteArrayResource pdf(Map<String,Object> file)throws Exception{
  String name=String.valueOf(file.get("original_name"));String ext=extension(name);
  if(!SUPPORTED.contains(ext))throw new IllegalArgumentException("该文件不是可转换的 Office 文档");
  long id=((Number)file.get("id")).longValue();long size=((Number)file.get("size_bytes")).longValue();
  Path cached=cacheRoot.resolve(id+"-"+size+".pdf");
  if(Files.exists(cached)&&Files.size(cached)>0)return new ByteArrayResource(Files.readAllBytes(cached));
  Object lock=locks.computeIfAbsent(id,key->new Object());
  try{synchronized(lock){
   if(Files.exists(cached)&&Files.size(cached)>0)return new ByteArrayResource(Files.readAllBytes(cached));
   return convert(file,name,ext,cached);
  }}finally{locks.remove(id,lock);}
 }

 public boolean supports(String name){return SUPPORTED.contains(extension(name));}
 public void evict(long id){try(var paths=Files.list(cacheRoot)){paths.filter(path->path.getFileName().toString().startsWith(id+"-")).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception ignored){}});}catch(Exception ignored){}}

 private ByteArrayResource convert(Map<String,Object> file,String originalName,String ext,Path cached)throws Exception{
  Path work=Files.createTempDirectory(cacheRoot,"job-");
  String base="document";Path input=work.resolve(base+"."+ext),log=work.resolve("convert.log");
  try(InputStream in=storage.download(file).getInputStream()){Files.copy(in,input,StandardCopyOption.REPLACE_EXISTING);}
  Process process;
  String isolatedProfile="-env:UserInstallation="+work.resolve("profile").toUri();
  try{process=new ProcessBuilder(command,isolatedProfile,"--headless","--nologo","--nodefault","--nofirststartwizard","--norestore","--convert-to","pdf","--outdir",work.toString(),input.toString())
    .redirectErrorStream(true).redirectOutput(log.toFile()).start();}
  catch(Exception e){deleteTree(work);throw new IllegalArgumentException("Office 预览服务未安装或未配置，请安装 LibreOffice 后重试");}
  boolean finished=process.waitFor(timeout.toSeconds(),TimeUnit.SECONDS);
  if(!finished){process.destroyForcibly();deleteTree(work);throw new IllegalArgumentException("Office 文件转换超时，请下载后查看");}
  Path output=work.resolve(base+".pdf");
  if(process.exitValue()!=0||!Files.exists(output)||Files.size(output)==0){String detail=Files.exists(log)?Files.readString(log):"";deleteTree(work);throw new IllegalArgumentException("Office 文件转换失败"+(detail.isBlank()?"":"："+detail.lines().findFirst().orElse("")));}
  Files.move(output,cached,StandardCopyOption.REPLACE_EXISTING);byte[] bytes=Files.readAllBytes(cached);deleteTree(work);return new ByteArrayResource(bytes);
 }

 private String extension(String name){int dot=name.lastIndexOf('.');return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);}
 private void deleteTree(Path root){if(root==null||!Files.exists(root))return;try(var paths=Files.walk(root)){paths.sorted((a,b)->b.compareTo(a)).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception ignored){}}); }catch(Exception ignored){} }
}
