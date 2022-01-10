package net.zhuoweizhang.raspberryjuice;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

interface ObjectConverter<To> {
	public To convert(Object obj);
}

class JSONTools {
	static public <To> ArrayList<To> ToArrayList(JSONArray arrayObject, ObjectConverter<To> converter) {
		ArrayList<To> array = new ArrayList<To>();
		for (Object object : arrayObject) {
			To convertedObject = converter.convert(object);
			if (convertedObject == null) {
				System.console().printf("Json convert failed for object %s\n", (String) object);
			} else {
				array.add(convertedObject);
			}
		}

		return array;
	}
}

class Vector3 {
	public long x, y, z;

	public Vector3(JSONObject obj) {
		x = (long) obj.get("x");
		y = (long) obj.get("y");
		z = (long) obj.get("z");
	}
};

class Region {
	public Vector3 min;
	public Vector3 max;
	public Vector3 center;

	public Region(JSONObject regionObject) {
		update(regionObject);
	}

	public void update(JSONObject regionObject) {
		this.min = new Vector3((JSONObject) regionObject.get("min"));
		this.max = new Vector3((JSONObject) regionObject.get("max"));
		this.center = new Vector3((JSONObject) regionObject.get("center"));
	}

	public boolean containsPoint(Location point) {
		return (min.x <= point.getBlockX() && point.getBlockX() <= max.x)
				&& (min.y <= point.getBlockY() && point.getBlockY() <= max.y)
				&& (min.z <= point.getBlockZ() && point.getBlockZ() <= max.z);
	}

	public Location getCenter(World world) {
		return new Location(world, center.x, center.y, center.z);
	}
};

class AreaPermission {
	public ArrayList<Region> banAreas = new ArrayList<Region>();

	public ArrayList<Region> visitAreas = new ArrayList<Region>();

	public ArrayList<Region> editAreas = new ArrayList<Region>();

	public AreaPermission(JSONObject object) {
		JSONArray banAreaNames = (JSONArray) object.get("ban_areas");
		banAreas = JSONTools.ToArrayList(banAreaNames, obj -> MineBackend.getArea((String) obj));

		JSONArray visitAreaNames = (JSONArray) object.get("visit_areas");
		visitAreas = JSONTools.ToArrayList(visitAreaNames, obj -> MineBackend.getArea((String) obj));

		JSONArray editAreaNames = (JSONArray) object.get("edit_areas");
		editAreas = JSONTools.ToArrayList(editAreaNames, obj -> MineBackend.getArea((String) obj));
	}

	public boolean isBanned(Location loc) {
		for (Region area : banAreas) {
			if (area.containsPoint(loc)) {
				return true;
			}
		}

		return false;
	}

	public boolean allowVisit(Location loc) {
		for (Region area : visitAreas) {
			if (area.containsPoint(loc)) {
				return true;
			}
		}

		for (Region area : editAreas) {
			if (area.containsPoint(loc)) {
				return true;
			}
		}

		return false;
	}

	public boolean allowEdit(Location loc) {
		for (Region area : editAreas) {
			if (area.containsPoint(loc)) {
				return true;
			}
		}

		return false;
	}
}

class UserGroupBackend {
	// private String name;

	private UserGroupBackend parent;

	private AreaPermission permission;

	// private ArrayList<String> members;

	public UserGroupBackend() {
	}

	public UserGroupBackend(JSONObject userGroupObject) {
		// this.name = (String) userGroupObject.get("name");
		update(userGroupObject);
	}

	public void update(JSONObject userGroupObject) {
		String parentName = (String) userGroupObject.get("parent");
		if (parentName != null) {
			this.parent = MineBackend.getUserGroup(parentName);
		}
		this.permission = new AreaPermission(userGroupObject);

		// JSONArray memberNames = (JSONArray) userGroupObject.get("members");
		// this.members = JSONTools.ToArrayList(memberNames, obj -> (String) obj);
	}

	public boolean allowVisit(Location loc) {
		if (permission.allowVisit(loc) || permission.allowEdit(loc)) {
			return true;
		}

		if (permission.isBanned(loc)) {
			return false;
		}

		if (parent != null) {
			if (parent.allowVisit(loc) || parent.allowEdit(loc)) {
				return true;
			}
		}

		return false;
	}

	public boolean allowEdit(Location loc) {
		if (permission.allowEdit(loc)) {
			return true;
		}

		if (permission.isBanned(loc)) {
			return false;
		}

		if (parent != null) {
			if (parent.allowEdit(loc)) {
				return true;
			}
		}

		return false;
	}

	Region findAnyAllowedRegion() {
		if (!permission.editAreas.isEmpty()) {
			return permission.editAreas.get(0);
		}
		if (!permission.visitAreas.isEmpty()) {
			return permission.visitAreas.get(0);
		}
		if (parent != null) {
			return parent.findAnyAllowedRegion();
		}
		return null;
	}
}

class UserBackend {
	// private String name;

	private ArrayList<UserGroupBackend> userGroups;

	private Region homeArea;

	private AreaPermission permission;

	public UserBackend(JSONObject userObject) {
		// this.name = (String) userObject.get("name");
		update(userObject);
	}

	public void update(JSONObject userObject) {
		String homeName = (String) userObject.get("home");
		this.homeArea = MineBackend.getArea(homeName);

		JSONArray userGroupNames = (JSONArray) userObject.get("groups");
		this.userGroups = JSONTools.ToArrayList(userGroupNames, obj -> MineBackend.getUserGroup((String) obj));

		this.permission = new AreaPermission(userObject);
	}

	public boolean allowVisit(Location loc) {
		if (homeArea != null) {
			if (homeArea.containsPoint(loc)) {
				return true;
			}
		}

		if (permission.allowVisit(loc) || permission.allowEdit(loc)) {
			return true;
		}

		if (permission.isBanned(loc)) {
			return false;
		}

		for (UserGroupBackend group : userGroups) {
			if (group.allowVisit(loc) || group.allowEdit(loc)) {
				return true;
			}
		}

		return false;
	}

	Region findAnyAllowedRegion() {
		if (!permission.editAreas.isEmpty()) {
			return permission.editAreas.get(0);
		}
		if (!permission.visitAreas.isEmpty()) {
			return permission.visitAreas.get(0);
		}

		for (UserGroupBackend group : userGroups) {
			Region allowedRegion = group.findAnyAllowedRegion();
			if (allowedRegion != null) {
				return allowedRegion;
			}
		}

		return null;
	}

	public boolean allowEdit(Location loc) {
		if (permission.allowEdit(loc)) {
			return true;
		}

		if (permission.isBanned(loc)) {
			return false;
		}

		for (UserGroupBackend group : userGroups) {
			if (group.allowEdit(loc)) {
				return true;
			}
		}

		return false;
	}

	@SuppressWarnings("deprecation")
	public void updateBlock(Block thisBlock, int blockType, byte blockData) {
		// check to see if the block is different - otherwise leave it
		if ((thisBlock.getTypeId() != blockType) || (thisBlock.getData() != blockData)) {
			thisBlock.setTypeIdAndData(blockType, blockData, false);
		}
	}

	public Region getHome() {
		return homeArea;
	}
}

public class MineBackend {

	private static HashMap<String, UserGroupBackend> registeredUserGroups = new HashMap<String, UserGroupBackend>();

	private static HashMap<String, UserBackend> registeredUsers = new HashMap<String, UserBackend>();

	private static HashMap<String, Region> registeredAreas = new HashMap<String, Region>();

	private static JSONParser parser = new JSONParser();

	private static Socket backendSocket;

	public static boolean isUserRegistered(String userName) {
		return registeredUsers.containsKey(userName);
	}

	public static UserBackend getUser(String userName) {
		return registeredUsers.get(userName);
	}

	public static Region getArea(String areaName) {
		return registeredAreas.get(areaName);
	}

	public static UserGroupBackend getUserGroup(String userGroupName) {
		return registeredUserGroups.get(userGroupName);
	}

	public static void refreshAll(RaspberryJuicePlugin plugin) throws Exception {
		refreshAreas(plugin);
		refreshUserGroups(plugin);
		refreshUsers(plugin);
	}

	public static void refreshAreas(RaspberryJuicePlugin plugin) throws Exception {
		plugin.getLogger().info("Refreshing all areas...");

		String returnBody = RaspberryJuicePlugin.makeRequest(new URI(RaspberryJuicePlugin.backendUrl + "/area/all"));

		JSONArray areas = (JSONArray) parser.parse(returnBody);
		for (int i = 0; i < areas.size(); i++) {
			JSONObject areaObject = (JSONObject) areas.get(i);
			String name = (String) areaObject.get("name");

			if (name == null || name.isEmpty()) {
				continue;
			}

			JSONObject regionObject = (JSONObject) areaObject.get("region");
			Region region = registeredAreas.get(name);
			if (region == null) {
				registeredAreas.put(name, new Region(regionObject));
			} else {
				region.update(regionObject);
			}

			plugin.getLogger().info("Updated area " + name);
		}
	}

	public static void refreshArea(String areaName, RaspberryJuicePlugin plugin) throws Exception {
		String returnBody = RaspberryJuicePlugin
				.makeRequest(new URI(RaspberryJuicePlugin.backendUrl + "/area/get/" + areaName));
		JSONObject returnObject = (JSONObject) parser.parse(returnBody);

		JSONObject regionObject = (JSONObject) returnObject.get("region");
		Region region = registeredAreas.get(areaName);
		if (region == null) {
			registeredAreas.put(areaName, new Region(regionObject));
		} else {
			region.update(regionObject);
		}

		plugin.getLogger().info("Updated area " + areaName);
	}

	public static void refreshUsers(RaspberryJuicePlugin plugin) throws Exception {
		plugin.getLogger().info("Refreshing all users...");

		String returnBody = RaspberryJuicePlugin
				.makeRequest(new URI(RaspberryJuicePlugin.backendUrl + "/user/all"));

		JSONArray users = (JSONArray) parser.parse(returnBody);
		for (int i = 0; i < users.size(); i++) {
			JSONObject userObject = (JSONObject) users.get(i);
			String name = (String) userObject.get("name");

			if (name == null || name.isEmpty()) {
				continue;
			}

			UserBackend user = registeredUsers.get(name);
			if (user == null) {
				registeredUsers.put(name, new UserBackend(userObject));
			} else {
				user.update(userObject);
			}

			plugin.getLogger().info("Updated user " + name);
		}
	}

	public static void refreshUser(String name, RaspberryJuicePlugin plugin) throws Exception {
		String returnBody = RaspberryJuicePlugin
				.makeRequest(new URI(RaspberryJuicePlugin.backendUrl + "/user/get/" + name));
		JSONObject returnObject = (JSONObject) parser.parse(returnBody);

		UserBackend user = registeredUsers.get(name);
		if (user == null) {
			registeredUsers.put(name, new UserBackend(returnObject));
		} else {
			user.update(returnObject);
		}

		plugin.getLogger().info("Updated user " + name);
	}

	public static void refreshUserGroups(RaspberryJuicePlugin plugin) throws Exception {
		plugin.getLogger().info("Refreshing all user groups...");

		String returnBody = RaspberryJuicePlugin
				.makeRequest(new URI(RaspberryJuicePlugin.backendUrl + "/user_group/all"));

		JSONArray userGroups = (JSONArray) parser.parse(returnBody);
		for (int i = 0; i < userGroups.size(); i++) {
			JSONObject userObject = (JSONObject) userGroups.get(i);
			String name = (String) userObject.get("name");

			if (name == null || name.isEmpty()) {
				continue;
			}

			String parentName = (String) userObject.get("parent");
			if (parentName != null && !parentName.isEmpty()) {
				if (!registeredUserGroups.containsKey(parentName)) {
					registeredUserGroups.put(name, new UserGroupBackend());
				}
			}

			UserGroupBackend userGroup = registeredUserGroups.get(name);
			if (userGroup == null) {
				registeredUserGroups.put(name, new UserGroupBackend(userObject));
			} else {
				userGroup.update(userObject);
			}

			plugin.getLogger().info("Updated user group " + name);
		}
	}

	public static void refreshUserGroup(String userGroupName, RaspberryJuicePlugin plugin) throws Exception {
		String userString = RaspberryJuicePlugin
				.makeRequest(new URI(RaspberryJuicePlugin.backendUrl + "/user_group/get/" + userGroupName));
		JSONObject userObject = (JSONObject) parser.parse(userString);

		UserGroupBackend userGroup = registeredUserGroups.get(userGroupName);
		if (userGroup == null) {
			registeredUserGroups.put(userGroupName, new UserGroupBackend(userObject));
		} else {
			userGroup.update(userObject);
		}

		plugin.getLogger().info("Updated user group " + userGroupName);
	}

	public static void removeArea(String areaName, RaspberryJuicePlugin plugin) throws Exception {
		registeredAreas.remove(areaName);
		plugin.getLogger().info("Removed area " + areaName);
	}

	public static void removeUser(String userName, RaspberryJuicePlugin plugin) throws Exception {
		registeredUsers.remove(userName);
		plugin.getLogger().info("Removed user " + userName);
	}

	public static void removeUserGroup(String userGroupName, RaspberryJuicePlugin plugin) throws Exception {
		registeredUserGroups.remove(userGroupName);
		plugin.getLogger().info("Removed user group " + userGroupName);
	}

	public static void setupBackendSocket(String serverAddress, RaspberryJuicePlugin plugin) throws Exception {
		IO.Options options = IO.Options.builder()
				.setPath("/callback-service")
				.build();

		final RaspberryJuicePlugin instance = plugin;

		backendSocket = IO.socket(serverAddress, options);

		backendSocket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Socket connected: " + backendSocket.id());
			}
		});

		backendSocket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Socket disconnected: " + backendSocket.id()); // null
			}
		});

		backendSocket.on(Socket.EVENT_CONNECT_ERROR, new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Socket connection failed."); // null
			}
		});

		// backendSocket.on("refreshAreas", new Emitter.Listener() {
		// 	@Override
		// 	public void call(Object... args) {
		// 		System.out.println("Socket event received: refreshAreas");
		// 		try {
		// 			MineBackend.refreshAreas(instance);
		// 		} catch (Exception e) {
		// 			System.out.println("Socket event failed: refreshAreas");
		// 			e.printStackTrace();
		// 		}
		// 	}
		// });

		backendSocket.on("refreshArea", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Socket event received: refreshArea");
				try {
					MineBackend.refreshArea((String) args[0], instance);
				} catch (Exception e) {
					System.out.println("Socket event failed: refreshArea");
					e.printStackTrace();
				}
			}
		});

		// backendSocket.on("refreshUsers", new Emitter.Listener() {
		// 	@Override
		// 	public void call(Object... args) {
		// 		System.out.println("Socket event received: refreshUsers");
		// 		try {
		// 			MineBackend.refreshUsers(instance);
		// 		} catch (Exception e) {
		// 			System.out.println("Socket event failed: refreshUsers");
		// 			e.printStackTrace();
		// 		}
		// 	}
		// });

		backendSocket.on("refreshUser", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Socket event received: refreshUser");
				try {
					MineBackend.refreshUser((String) args[0], instance);
				} catch (Exception e) {
					System.out.println("Socket event failed: refreshUser");
					e.printStackTrace();
				}
			}
		});

		// backendSocket.on("refreshUserGroups", new Emitter.Listener() {
		// 	@Override
		// 	public void call(Object... args) {
		// 		System.out.println("Socket event received: refreshUserGroups");
		// 		try {
		// 			MineBackend.refreshUserGroups(instance);
		// 		} catch (Exception e) {
		// 			System.out.println("Socket event failed: refreshUserGroups");
		// 			e.printStackTrace();
		// 		}
		// 	}
		// });

		backendSocket.on("refreshUserGroup", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Socket event received: refreshUserGroup");
				try {
					MineBackend.refreshUserGroup((String) args[0], instance);
				} catch (Exception e) {
					System.out.println("Socket event failed: refreshUserGroup");
					e.printStackTrace();
				}
			}
		});

		backendSocket.on("removeArea", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Socket event received: removeArea");
				try {
					MineBackend.removeArea((String) args[0], instance);
				} catch (Exception e) {
					System.out.println("Socket event failed: removeArea");
					e.printStackTrace();
				}
			}
		});

		backendSocket.on("removeUser", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Socket event received: removeUser");
				try {
					MineBackend.removeUser((String) args[0], instance);
				} catch (Exception e) {
					System.out.println("Socket event failed: removeUser");
					e.printStackTrace();
				}
			}
		});

		backendSocket.on("removeUserGroup", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Socket event received: removeUserGroup");
				try {
					MineBackend.removeUserGroup((String) args[0], instance);
				} catch (Exception e) {
					System.out.println("Socket event failed: removeUserGroup");
					e.printStackTrace();
				}
			}
		});

		backendSocket.connect();
	}
}
