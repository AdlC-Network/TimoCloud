package cloud.timo.TimoCloud.core.objects;

import cloud.timo.TimoCloud.api.objects.ServerGroupObject;
import cloud.timo.TimoCloud.core.TimoCloudCore;
import cloud.timo.TimoCloud.core.api.ServerGroupObjectCoreImplementation;
import org.json.simple.JSONObject;

import java.util.*;
import java.util.stream.Collectors;

public class ServerGroup implements Group {

    private String name;
    private List<Server> servers = new ArrayList<>();
    private int onlineAmount;
    private int maxAmount;
    private int ram;
    private boolean isStatic;
    private int priority;
    private String baseName;
    private List<String> sortOutStates;

    public ServerGroup() {
    }

    public ServerGroup(JSONObject jsonObject) {
        construct(jsonObject);
    }

    public ServerGroup(String name, int onlineAmount, int maxAmount, int ram, boolean isStatic, int priority, String baseName, List<String> sortOutStates) {
        construct(name, onlineAmount, maxAmount, ram, isStatic, priority, baseName, sortOutStates);
    }

    public void construct(JSONObject jsonObject) {
        try {
            construct(
                    (String) jsonObject.get("name"),
                    (Integer) jsonObject.getOrDefault("online-amount", 1),
                    (Integer) jsonObject.getOrDefault("max-amount", 10),
                    (Integer) jsonObject.getOrDefault("ram", 1024),
                    (Boolean) jsonObject.getOrDefault("static", false),
                    (Integer) jsonObject.getOrDefault("priority", 1),
                    (String) jsonObject.getOrDefault("base", null),
                    (List<String>) jsonObject.getOrDefault("sort-out-states", Arrays.asList("OFFLINE", "STARTING", "RESTARTING")));
        } catch (Exception e) {
            TimoCloudCore.getInstance().severe("Error while loading server group '" + (String) jsonObject.get("name") + "':");
            e.printStackTrace();
        }
    }

    public JSONObject toJsonObject() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", getName());
        properties.put("online-amount", getOnlineAmount());
        properties.put("max-amount", getMaxAmount());
        properties.put("ram", getRam());
        properties.put("static", isStatic());
        properties.put("priority", getPriority());
        if (getBaseName() != null) properties.put("base", getBaseName());
        properties.put("sort-out-states", getSortOutStates());
        return new JSONObject(properties);
    }

    public void construct(String name, int startupAmount, int maxAmount, int ram, boolean isStatic, int priority, String baseName, List<String> sortOutStates) {
        if (isStatic() && startupAmount > 1) {
            TimoCloudCore.getInstance().severe("Static groups (" + name + ") can only have 1 server. Please set 'onlineAmount' to 1");
            return;
        }
        this.name = name;
        setOnlineAmount(startupAmount);
        setMaxAmount(maxAmount);
        if (ram <=128) ram*=1024;
        setRam(ram);
        setStatic(isStatic);
        setBaseName(baseName);
        setSortOutStates(sortOutStates);
        if (isStatic() && getBaseName() == null) {
            TimoCloudCore.getInstance().severe("Static server group " + getName() + " has no base specified. Please specify a base name in order to get the group started.");
        }
    }

    public String getName() {
        return name;
    }

    public int getOnlineAmount() {
        return onlineAmount;
    }

    public void stopAllServers() {
        List<Server> servers = (ArrayList<Server>) ((ArrayList<Server>) getServers()).clone();
        for (Server server : servers) server.stop();
        this.servers.removeAll(servers);
    }

    public void onServerConnect(Server server) {

    }

    public void addStartingServer(Server server) {
        if (server == null) TimoCloudCore.getInstance().severe("Fatal error: Tried to add server which is null. Please report this.");
        if (servers.contains(server)) TimoCloudCore.getInstance().severe("Tried to add already existing starting server " + server + ". Please report this.");
        servers.add(server);
    }

    public void removeServer(Server server) {
        if (servers.contains(server)) servers.remove(server);
    }

    public List<Server> getServers() {
        return servers;
    }

    public void setOnlineAmount(int onlineAmount) {
        this.onlineAmount = onlineAmount;
    }

    public void setMaxAmount(int maxAmount) {
        this.maxAmount = maxAmount;
    }

    public int getMaxAmount() {
        return maxAmount;
    }

    public int getRam() {
        return ram;
    }

    public void setRam(int ram) {
        if (ram < 128) TimoCloudCore.getInstance().severe("Attention: ServerGroup " + name + " has less than 128MB Ram. (This won't work)");
        this.ram = ram;
    }

    public void setStatic(boolean isStatic) {
        this.isStatic = isStatic;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public void setBaseName(String baseName) {
        this.baseName = baseName;
    }

    public String getBaseName() {
        return baseName;
    }

    public List<String> getSortOutStates() {
        return sortOutStates;
    }

    public void setSortOutStates(List<String> sortOutStates) {
        this.sortOutStates = sortOutStates;
    }

    public ServerGroupObject toGroupObject() {
        ServerGroupObjectCoreImplementation groupObject = new ServerGroupObjectCoreImplementation(
                servers.stream().map(Server::toServerObject).collect(Collectors.toList()),
                getName(),
                getOnlineAmount(),
                getMaxAmount(),
                getRam(),
                isStatic(),
                getBaseName(),
                getSortOutStates()
        );
        Collections.sort((List) groupObject.getServers());
        return groupObject;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServerGroup that = (ServerGroup) o;
        return name.equals(that.name);

    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return getName();
    }

}
