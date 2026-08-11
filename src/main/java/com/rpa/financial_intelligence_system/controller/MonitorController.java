package com.rpa.financial_intelligence_system.controller;

import com.rpa.financial_intelligence_system.common.ApiResponse;
import com.sun.management.OperatingSystemMXBean;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.io.File;
import java.lang.management.*;
import java.net.InetAddress;
import java.time.Instant;
import java.util.*;
import java.nio.file.*;
import java.util.regex.*;

@Tag(name="系统监控",description="服务器、JVM、磁盘、Redis 和 PostgreSQL 实时运行指标")
@RestController @RequestMapping("/api/monitor")
public class MonitorController {
 private final JdbcClient jdbc; private final StringRedisTemplate redis;
 public MonitorController(JdbcClient jdbc,StringRedisTemplate redis){this.jdbc=jdbc;this.redis=redis;}

 @Operation(summary="获取系统实时监控指标") @GetMapping("/system")
 @PreAuthorize("hasAuthority('system:monitor:list')")
 ApiResponse<?> system(){var result=new LinkedHashMap<String,Object>();result.put("timestamp",Instant.now());result.put("server",server());result.put("jvm",jvm());result.put("disks",disks());result.put("redis",redis());result.put("database",database());return ApiResponse.ok(result);}

 private Map<String,Object> server(){var os=(OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();var map=new LinkedHashMap<String,Object>();try{map.put("hostname",InetAddress.getLocalHost().getHostName());map.put("ip",InetAddress.getLocalHost().getHostAddress());}catch(Exception e){map.put("hostname","unknown");}long total=os.getTotalMemorySize(),rawFree=os.getFreeMemorySize(),available=availableMemory(rawFree),used=Math.max(0,total-available);map.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));map.put("arch",System.getProperty("os.arch"));map.put("processors",os.getAvailableProcessors());map.put("cpuUsage",round(os.getCpuLoad()*100));map.put("processCpuUsage",round(os.getProcessCpuLoad()*100));map.put("loadAverage",round(os.getSystemLoadAverage()));map.put("totalMemory",total);map.put("freeMemory",available);map.put("rawFreeMemory",rawFree);map.put("usedMemory",used);map.put("memoryUsage",percent(used,total));map.put("memoryMetric","AVAILABLE");return map;}
 private Map<String,Object> jvm(){MemoryUsage heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage(),nonHeap=ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();RuntimeMXBean runtime=ManagementFactory.getRuntimeMXBean();return Map.of("javaVersion",System.getProperty("java.version"),"jvmName",runtime.getVmName(),"heapUsed",heap.getUsed(),"heapMax",heap.getMax(),"heapUsage",percent(heap.getUsed(),heap.getMax()),"nonHeapUsed",nonHeap.getUsed(),"threads",ManagementFactory.getThreadMXBean().getThreadCount(),"peakThreads",ManagementFactory.getThreadMXBean().getPeakThreadCount(),"uptime",runtime.getUptime());}
 private List<Map<String,Object>> disks(){List<Map<String,Object>> list=new ArrayList<>();for(File f:File.listRoots()){long total=f.getTotalSpace(),free=f.getUsableSpace();list.add(Map.of("path",f.getAbsolutePath(),"total",total,"free",free,"used",total-free,"usage",percent(total-free,total)));}return list;}
 private Map<String,Object> redis(){try{return redis.execute((RedisCallback<Map<String,Object>>)c->{Properties p=c.serverCommands().info();Long keys=c.serverCommands().dbSize();var m=new LinkedHashMap<String,Object>();m.put("status","UP");m.put("version",p.getProperty("redis_version","-"));m.put("mode",p.getProperty("redis_mode","standalone"));m.put("uptimeSeconds",number(p.getProperty("uptime_in_seconds")));m.put("connectedClients",number(p.getProperty("connected_clients")));m.put("usedMemory",number(p.getProperty("used_memory")));m.put("usedMemoryHuman",p.getProperty("used_memory_human","-"));m.put("totalCommands",number(p.getProperty("total_commands_processed")));m.put("keys",keys==null?0:keys);return m;});}catch(Exception e){return Map.of("status","DOWN","error",e.getMessage()==null?"Redis 连接异常":e.getMessage());}}
 private Map<String,Object> database(){try{var m=new LinkedHashMap<String,Object>();m.put("status","UP");m.put("version",jdbc.sql("SHOW server_version").query(String.class).single());m.put("database",jdbc.sql("SELECT current_database()").query(String.class).single());m.put("size",jdbc.sql("SELECT pg_database_size(current_database())").query(Long.class).single());m.put("connections",jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()").query(Long.class).single());m.put("activeConnections",jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='active'").query(Long.class).single());m.put("maxConnections",number(jdbc.sql("SHOW max_connections").query(String.class).single()));m.put("transactions",jdbc.sql("SELECT xact_commit+xact_rollback FROM pg_stat_database WHERE datname=current_database()").query(Long.class).single());return m;}catch(Exception e){return Map.of("status","DOWN","error",e.getMessage()==null?"数据库连接异常":e.getMessage());}}
 private long availableMemory(long fallback){try{String name=System.getProperty("os.name").toLowerCase();if(name.contains("mac")){String out=new String(new ProcessBuilder("vm_stat").start().getInputStream().readAllBytes());Matcher page=Pattern.compile("page size of (\\d+) bytes").matcher(out);long pageSize=page.find()?Long.parseLong(page.group(1)):4096,totalPages=0;for(String key:List.of("Pages free","Pages inactive","Pages speculative","Pages purgeable")){Matcher m=Pattern.compile(Pattern.quote(key)+":\\s+(\\d+)").matcher(out);if(m.find())totalPages+=Long.parseLong(m.group(1));}return totalPages>0?totalPages*pageSize:fallback;}if(name.contains("linux")){for(String line:Files.readAllLines(Path.of("/proc/meminfo")))if(line.startsWith("MemAvailable:"))return Long.parseLong(line.replaceAll("\\D+",""))*1024;}return fallback;}catch(Exception e){return fallback;}}
 private double percent(long used,long total){return total<=0?0:round(used*100d/total);}private double round(double v){return v<0?0:Math.round(v*100d)/100d;}private long number(String v){try{return Long.parseLong(v);}catch(Exception e){return 0;}}
}
