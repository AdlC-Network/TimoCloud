package cloud.timo.TimoCloud.base.managers;

import cloud.timo.TimoCloud.base.TimoCloudBase;
import cloud.timo.TimoCloud.base.objects.BaseProxyObject;
import cloud.timo.TimoCloud.base.objects.BaseServerObject;
import cloud.timo.TimoCloud.utils.HashUtil;
import cloud.timo.TimoCloud.utils.ServerToGroupUtil;
import cloud.timo.TimoCloud.utils.TimeUtil;
import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BaseServerManager {

    private static final long STATIC_CREATE_TIME = 1482773874000L;

    private LinkedList<BaseServerObject> serverQueue;
    private LinkedList<BaseProxyObject> proxyQueue;

    private final ScheduledExecutorService scheduler;

    private boolean startingServer = false;
    private boolean startingProxy = false;

    public BaseServerManager(long millis) {
        serverQueue = new LinkedList<>();
        proxyQueue = new LinkedList<>();
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::startNext, millis, millis, TimeUnit.MILLISECONDS);
    }

    public void updateResources() {
        boolean ready = serverQueue.isEmpty() && !startingServer && !startingProxy && TimoCloudBase.getInstance().getResourceManager().getCpuUsage() <= (Double) TimoCloudBase.getInstance().getFileManager().getConfig().get("cpu-max-load");
        int freeRam = Math.max(0, TimoCloudBase.getInstance().getResourceManager().getFreeMemory() - (Integer) TimoCloudBase.getInstance().getFileManager().getConfig().get("ram-keep-free"));
        Map<String, Object> resourcesMap = new HashMap<>();
        resourcesMap.put("ready", ready);
        resourcesMap.put("availableRam", freeRam);
        resourcesMap.put("maxRam", TimoCloudBase.getInstance().getFileManager().getConfig().get("ram"));
        TimoCloudBase.getInstance().getSocketMessageManager().sendMessage("RESOURCES", new JSONObject(resourcesMap));
    }

    public void addToServerQueue(BaseServerObject server) {
        serverQueue.add(server);
    }

    public void addToProxyQueue(BaseProxyObject proxy) {
        proxyQueue.add(proxy);
    }

    public void startNext() {
        startNextServer();
        startNextProxy();
        updateResources();
    }

    public void startNextServer() {
        if (serverQueue.isEmpty()) return;
        BaseServerObject server = serverQueue.pop();
        if (server == null) {
            startNextServer();
            return;
        }
        startingServer = true;
        startServer(server);
        startingServer = false;
    }

    public void startNextProxy() {
        if (proxyQueue.isEmpty()) return;
        BaseProxyObject proxy = proxyQueue.pop();
        if (proxy == null) {
            startNextProxy();
            return;
        }
        startingProxy = true;
        startProxy(proxy);
        startingProxy = false;
    }

    private void copyDirectory(File from, File to) throws IOException {
        FileUtils.copyDirectory(from, to);
    }

    private void copyDirectoryCarefully(File from, File to, long value, int layer) throws IOException {
        if (layer > 25) {
            throw new IOException("Too many layers. This could be caused by a symlink loop. File: " + to.getAbsolutePath());
        }
        for (File file : from.listFiles()) {
            File toFile = new File(to, file.getName());
            if (file.isDirectory()) {
                copyDirectoryCarefully(file, toFile, value, layer + 1);
            } else {
                if (toFile.exists() && toFile.lastModified() != value) continue;

                FileUtils.copyFileToDirectory(file, to);
                toFile.setLastModified(value);
            }
        }
    }

    private void deleteDirectory(File directory) {
        if (directory.exists()) FileDeleteStrategy.FORCE.deleteQuietly(directory);
    }

    private void startServer(BaseServerObject server) {
        TimoCloudBase.info("Starting server " + server.getName() + "...");
        double millisBefore = System.currentTimeMillis();
        try {
            File templateDirectory = new File((server.isStatic() ? TimoCloudBase.getInstance().getFileManager().getServerStaticDirectory() : TimoCloudBase.getInstance().getFileManager().getServerTemplatesDirectory()), server.getGroup());
            if (!templateDirectory.exists()) {
                TimoCloudBase.severe("Could not start server " + server.getName() + ": No template called " + server.getGroup() + " found. Please make sure the directory " + templateDirectory.getAbsolutePath() + " exists. (Put your minecraft server in there)");
                return;
            }

            List<String> templateDifferences = HashUtil.getDifferentFiles("", server.getTemplateHash(), HashUtil.getHashes(templateDirectory));
            List<String> mapDifferences = server.getMapHash() != null ? HashUtil.getDifferentFiles("", server.getMapHash(), HashUtil.getHashes(new File(TimoCloudBase.getInstance().getFileManager().getServerTemplatesDirectory(), server.getGroup() + "_" + server.getMap()))) : new ArrayList<>();
            List<String> gloalDifferences = HashUtil.getDifferentFiles("", server.getGlobalHash(), HashUtil.getHashes(TimoCloudBase.getInstance().getFileManager().getServerGlobalDirectory()));

            if (templateDifferences.size() > 0 || mapDifferences.size() > 0 || gloalDifferences.size() > 0) {
                Map<String, Object> differences = new HashMap<>();
                if (templateDifferences.size() > 0) differences.put("templateDifferences", templateDifferences);
                if (mapDifferences.size() > 0) differences.put("mapDifferences", mapDifferences);
                if (gloalDifferences.size() > 0) differences.put("globalDifferences", gloalDifferences);
                TimoCloudBase.info("New server template updates found! Stopping and downloading updates...");
                JSONObject differencesJson = new JSONObject(differences);
                Map<String, Object> message = new HashMap<>();
                message.put("type", "SERVER_TEMPLATE_REQUEST");
                message.put("server", server.getName());
                if (templateDifferences.size() > 0) message.put("template", templateDirectory.getName());
                if (mapDifferences.size() > 0) message.put("map", server.getMap());
                message.put("differences", differencesJson);
                TimoCloudBase.getInstance().getSocketMessageManager().sendMessage(new JSONObject(message));
                return;
            }

            File temporaryDirectory = server.isStatic() ? templateDirectory : new File(TimoCloudBase.getInstance().getFileManager().getServerTemporaryDirectory(), server.getName());
            if (!server.isStatic()) {
                if (temporaryDirectory.exists()) deleteDirectory(temporaryDirectory);
                copyDirectory(TimoCloudBase.getInstance().getFileManager().getServerGlobalDirectory(), temporaryDirectory);
            }

            if (server.isStatic()) {
                copyDirectoryCarefully(TimoCloudBase.getInstance().getFileManager().getServerGlobalDirectory(), temporaryDirectory, STATIC_CREATE_TIME, 1);
            } else {
                copyDirectory(templateDirectory, temporaryDirectory);
            }

            boolean randomMap = false;
            String mapName = server.getMap() == null ? "default" : server.getMap();
            if (!server.isStatic() && server.getMap() != null) {
                randomMap = true;
                File mapDirectory = new File(TimoCloudBase.getInstance().getFileManager().getServerTemplatesDirectory(), server.getGroup() + "_" + server.getMap());
                copyDirectory(mapDirectory, temporaryDirectory);
            }

            File spigotJar = new File(temporaryDirectory, "spigot.jar");
            if (!spigotJar.exists()) {
                TimoCloudBase.severe("Could not start server " + server.getName() + " because spigot.jar does not exist. Please make sure a the file " + spigotJar.getAbsolutePath() + " exists (case sensitive!)");
                return;
            }


            File plugins = new File(temporaryDirectory, "/plugins/");
            plugins.mkdirs();
            File plugin = new File(plugins, "TimoCloud.jar");
            if (plugin.exists()) plugin.delete();
            try {
                Files.copy(new File(TimoCloudBase.class.getProtectionDomain().getCodeSource().getLocation().getPath()).toPath(), plugin.toPath());
            } catch (Exception e) {
                TimoCloudBase.severe("Error while copying plugin into template:");
                e.printStackTrace();
                if (!plugin.exists()) return;
            }

            Integer port = getFreePort();
            if (port == null) {
                TimoCloudBase.severe("Error while starting server " + server.getName() + ": No free port found. Please report this!");
            }

            File serverProperties = new File(temporaryDirectory, "server.properties");
            setProperty(serverProperties, "online-mode", "false");
            setProperty(serverProperties, "server-name", server.getName());

            double millisNow = System.currentTimeMillis();
            TimoCloudBase.info("Successfully prepared starting server " + server.getName() + " in " + (millisNow - millisBefore) / 1000 + " seconds.");


            ProcessBuilder pb = new ProcessBuilder(
                    "/bin/sh", "-c",
                    "screen -mdS " + server.getName() +
                            " java -server " +
                            " -Xmx" + server.getRam() + "M" +
                            " -Dfile.encoding=UTF8 -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:+AggressiveOpts -XX:+DoEscapeAnalysis -XX:+UseCompressedOops -XX:MaxGCPauseMillis=10 -XX:GCPauseIntervalMillis=100 -XX:+UseAdaptiveSizePolicy -XX:ParallelGCThreads=2 -XX:UseSSE=3 " +
                            " -Dcom.mojang.eula.agree=true" +
                            " -Dtimocloud-bungeecordhost=" + TimoCloudBase.getInstance().getBungeeSocketIP() + ":" + TimoCloudBase.getInstance().getBungeeSocketPort() +
                            " -Dtimocloud-randommap=" + randomMap +
                            " -Dtimocloud-mapname=" + mapName +
                            " -Dtimocloud-servername=" + server.getName() +
                            " -Dtimocloud-static=" + server.isStatic() +
                            " -Dtimocloud-token=" + server.getToken() +
                            " -Dtimocloud-templatedirectory=" + templateDirectory.getAbsolutePath() +
                            " -Dtimocloud-temporarydirectory=" + temporaryDirectory.getAbsolutePath() +
                            " -jar spigot.jar -o false -h 0.0.0.0 -p " + port
            ).directory(temporaryDirectory);
            try {
                pb.start();
                TimoCloudBase.info("Successfully started screen session " + server.getName() + ".");
            } catch (Exception e) {
                TimoCloudBase.severe("Error while starting server " + server.getName() + ":");
                e.printStackTrace();
            }

            Map<String, Object> message = new HashMap<>();
            message.put("type", "SERVER_STARTED");
            message.put("server", server.getName());
            message.put("token", server.getToken());
            message.put("port", port);
            TimoCloudBase.getInstance().getSocketMessageManager().sendMessage(new JSONObject(message));

        } catch (Exception e) {
            TimoCloudBase.severe("Error while starting server " + server.getName() + ":");
            e.printStackTrace();
            TimoCloudBase.getInstance().getSocketMessageManager().sendMessage("SERVER_NOT_STARTED", server.getName(), server.getToken());
        }
    }


    private void startProxy(BaseProxyObject proxy) {
        TimoCloudBase.info("Starting proxy " + proxy.getName() + "...");
        double millisBefore = System.currentTimeMillis();
        try {
            File templateDirectory = new File((proxy.isStatic() ? TimoCloudBase.getInstance().getFileManager().getProxyStaticDirectory() : TimoCloudBase.getInstance().getFileManager().getProxyTemplatesDirectory()), proxy.getGroup());
            if (!templateDirectory.exists()) {
                TimoCloudBase.severe("Could not start proxy " + proxy.getName() + ": No template called " + proxy.getGroup() + " found. Please make sure the directory " + templateDirectory.getAbsolutePath() + " exists. (Put your BungeeCord in there)");
                return;
            }

            List<String> templateDifferences = HashUtil.getDifferentFiles("", proxy.getTemplateHash(), HashUtil.getHashes(templateDirectory));
            List<String> gloalDifferences = HashUtil.getDifferentFiles("", proxy.getGlobalHash(), HashUtil.getHashes(TimoCloudBase.getInstance().getFileManager().getProxyGlobalDirectory()));

            if (templateDifferences.size() > 0 || gloalDifferences.size() > 0) {
                Map<String, Object> differences = new HashMap<>();
                if (templateDifferences.size() > 0) differences.put("templateDifferences", templateDifferences);
                if (gloalDifferences.size() > 0) differences.put("globalDifferences", gloalDifferences);
                TimoCloudBase.info("New proxy template updates found! Stopping and downloading updates...");
                JSONObject differencesJson = new JSONObject(differences);
                Map<String, Object> message = new HashMap<>();
                message.put("type", "PROXY_TEMPLATE_REQUEST");
                message.put("proxy", proxy.getName());
                if (templateDifferences.size() > 0) message.put("template", templateDirectory.getName());
                message.put("differences", differencesJson);
                TimoCloudBase.getInstance().getSocketMessageManager().sendMessage(new JSONObject(message));
                return;
            }

            File temporaryDirectory = proxy.isStatic() ? templateDirectory : new File(TimoCloudBase.getInstance().getFileManager().getProxyTemplatesDirectory(), proxy.getName());
            if (!proxy.isStatic()) {
                if (temporaryDirectory.exists()) deleteDirectory(temporaryDirectory);
                copyDirectory(TimoCloudBase.getInstance().getFileManager().getProxyGlobalDirectory(), temporaryDirectory);
            }

            if (proxy.isStatic()) {
                copyDirectoryCarefully(TimoCloudBase.getInstance().getFileManager().getProxyGlobalDirectory(), temporaryDirectory, STATIC_CREATE_TIME, 1);
            } else {
                copyDirectory(templateDirectory, temporaryDirectory);
            }

            File bungeeJar = new File(temporaryDirectory, "BungeeCord.jar");
            if (!bungeeJar.exists()) {
                TimoCloudBase.severe("Could not start proxy " + proxy.getName() + " because BungeeCord.jar does not exist. Please make sure a the file " + bungeeJar.getAbsolutePath() + " exists (case sensitive!)");
                return;
            }


            File plugins = new File(temporaryDirectory, "/plugins/");
            plugins.mkdirs();
            File plugin = new File(plugins, "TimoCloud.jar");
            if (plugin.exists()) plugin.delete();
            try {
                Files.copy(new File(TimoCloudBase.class.getProtectionDomain().getCodeSource().getLocation().getPath()).toPath(), plugin.toPath());
            } catch (Exception e) {
                TimoCloudBase.severe("Error while copying plugin into template:");
                e.printStackTrace();
                if (!plugin.exists()) return;
            }

            Integer port = getFreePort();
            if (port == null) {
                TimoCloudBase.severe("Error while starting proxy " + proxy.getName() + ": No free port found. Please report this!");
            }

            File serverProperties = new File(temporaryDirectory, "server.properties");
            setProperty(serverProperties, "online-mode", "false");
            setProperty(serverProperties, "server-name", server.getName());

            double millisNow = System.currentTimeMillis();
            TimoCloudBase.info("Successfully prepared starting server " + server.getName() + " in " + (millisNow - millisBefore) / 1000 + " seconds.");


            ProcessBuilder pb = new ProcessBuilder(
                    "/bin/sh", "-c",
                    "screen -mdS " + server.getName() +
                            " java -server " +
                            " -Xmx" + server.getRam() + "M" +
                            " -Dfile.encoding=UTF8 -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:+AggressiveOpts -XX:+DoEscapeAnalysis -XX:+UseCompressedOops -XX:MaxGCPauseMillis=10 -XX:GCPauseIntervalMillis=100 -XX:+UseAdaptiveSizePolicy -XX:ParallelGCThreads=2 -XX:UseSSE=3 " +
                            " -Dcom.mojang.eula.agree=true" +
                            " -Dtimocloud-bungeecordhost=" + TimoCloudBase.getInstance().getBungeeSocketIP() + ":" + TimoCloudBase.getInstance().getBungeeSocketPort() +
                            " -Dtimocloud-randommap=" + randomMap +
                            " -Dtimocloud-mapname=" + mapName +
                            " -Dtimocloud-servername=" + server.getName() +
                            " -Dtimocloud-static=" + server.isStatic() +
                            " -Dtimocloud-token=" + server.getToken() +
                            " -Dtimocloud-templatedirectory=" + templateDirectory.getAbsolutePath() +
                            " -Dtimocloud-temporarydirectory=" + temporaryDirectory.getAbsolutePath() +
                            " -jar spigot.jar -o false -h 0.0.0.0 -p " + port
            ).directory(temporaryDirectory);
            try {
                pb.start();
                TimoCloudBase.info("Successfully started screen session " + server.getName() + ".");
            } catch (Exception e) {
                TimoCloudBase.severe("Error while starting server " + server.getName() + ":");
                e.printStackTrace();
            }

            Map<String, Object> message = new HashMap<>();
            message.put("type", "PROXY_STARTED");
            message.put("proxy", proxy.getName());
            message.put("token", proxy.getToken());
            message.put("port", port);
            TimoCloudBase.getInstance().getSocketMessageManager().sendMessage(new JSONObject(message));

        } catch (Exception e) {
            TimoCloudBase.severe("Error while starting proxy " + proxy.getName() + ":");
            e.printStackTrace();
            TimoCloudBase.getInstance().getSocketMessageManager().sendMessage("PROXY_NOT_STARTED", proxy.getName(), proxy.getToken());
        }
    }

    private Integer getFreePort() {
        for (int p = 40000; p<=50000; p++) {
            if (portIsFree(p)) return p;
        }
        return null;
    }

    private boolean portIsFree(int port) {
        try {
            new ServerSocket(port).close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void setProperty(File file, String property, String value) {
        try {
            file.createNewFile();

            FileInputStream in = new FileInputStream(file);
            Properties props = new Properties();
            props.load(in);
            in.close();

            FileOutputStream out = new FileOutputStream(file);
            props.setProperty(property, value);
            props.store(out, null);
            out.close();
        } catch (Exception e) {
            TimoCloudBase.severe("Error while setting property '" + property + "' to value '" + value + "' in file " + file.getAbsolutePath() + ":");
            e.printStackTrace();
        }
    }


    public void onServerStopped(String name) {
        File directory = new File(TimoCloudBase.getInstance().getFileManager().getServerTemporaryDirectory(), name);
        if ((Boolean) TimoCloudBase.getInstance().getFileManager().getConfig().get("save-logs")) {
            File log = new File(directory, "/logs/latest.log");
            if (log.exists()) {
                try {
                    File dir = new File(TimoCloudBase.getInstance().getFileManager().getLogsDirectory(), ServerToGroupUtil.getGroupByServer(name));
                    dir.mkdirs();
                    Files.copy(log.toPath(), new File(dir, TimeUtil.formatTime() + "_" + name + ".log").toPath());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                TimoCloudBase.severe("No log from server " + name + " exists.");
            }
        }
        deleteDirectory(directory);
    }
}
