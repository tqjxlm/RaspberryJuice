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

	public Region(Vector3 min, Vector3 max, Vector3 center) {
		this.min = min;
		this.max = max;
		this.center = center;
	}

	public boolean containsPoint(Location point) {
		return (min.x <= point.getBlockX() && point.getBlockX() <= max.x)
				&& (min.y <= point.getBlockY() && point.getBlockY() <= max.y)
				&& (min.z <= point.getBlockZ() && point.getBlockZ() <= max.z);
	}

	public Location getCenter(World world) {
		return new Location(world, center.x, center.y, center.z);
	}

	public static Region fromJson(JSONObject regionObject) {
		Vector3 min = new Vector3((JSONObject) regionObject.get("min"));
		Vector3 max = new Vector3((JSONObject) regionObject.get("max"));
		Vector3 center = new Vector3((JSONObject) regionObject.get("center"));
		return new Region(min, max, center);
	}
};

public class PlayerBackend {

	private Region homeArea;

	private String userName;

	private static ArrayList<Region> restrictedAreas = new ArrayList<Region>();

	private static HashMap<String, PlayerBackend> registeredPlayers = new HashMap<String, PlayerBackend>();

	private static HashMap<String, Region> registeredHomes = new HashMap<String, Region>();

	private static JSONParser parser = new JSONParser();

	private static Socket backendSocket;

	public PlayerBackend(String playerName, String homeName) throws Exception {
		this.userName = playerName;
		fetchHomeInfo(homeName);
	}

	public void fetchHomeInfo(String homeName) throws Exception {
		if (homeName != null) {
			this.homeArea = registeredHomes.get(homeName);
		}
	}

	public boolean isModificationAllowed(Location loc) {
		for (Region restrictedArea : restrictedAreas) {
			if (restrictedArea.containsPoint(loc)) {
				if (this.homeArea == null) {
					return false;
				} else {
					return homeArea.containsPoint(loc);
				}
			}
		}
		return true;
	}

	public void updateBlock(Block thisBlock, int blockType, byte blockData) {
		// check to see if the block is different - otherwise leave it
		if ((thisBlock.getTypeId() != blockType) || (thisBlock.getData() != blockData)) {
			thisBlock.setTypeIdAndData(blockType, blockData, false);
		}
	}

	public Region getHome() {
		return homeArea;
	}

	public static boolean isPlayerRegistered(String playerName) {
		return registeredPlayers.containsKey(playerName);
	}

	public static PlayerBackend getBackend(String playerName) {
		return registeredPlayers.get(playerName);
	}

	public static void refreshAll(RaspberryJuicePlugin plugin) throws Exception {
		refreshRestrictedAreas(plugin);
		refreshHomeAreas(plugin);
		refreshRegisteredPlayers(plugin);
	}

	public static void refreshRestrictedAreas(RaspberryJuicePlugin plugin) throws Exception {
		plugin.getLogger().info("Refreshing all registered areas...");

		restrictedAreas.clear();

		String restrictedAreaString = RaspberryJuicePlugin
				.makeRequest(new URI(RaspberryJuicePlugin.backendUrl + "/restricted_area/all"));

		JSONArray areas = (JSONArray) parser.parse(restrictedAreaString);
		for (int i = 0; i < areas.size(); i++) {
			JSONObject areaObject = (JSONObject) areas.get(i);
			String areaName = (String) areaObject.get("name");

			Region region = Region.fromJson((JSONObject) areaObject.get("region"));
			restrictedAreas.add(region);

			plugin.getLogger().info("Updated registered area " + areaName);
		}
	}

	public static void refreshHomeAreas(RaspberryJuicePlugin plugin) throws Exception {
		plugin.getLogger().info("Refreshing all home areas...");

		registeredHomes.clear();

		String homeAreaString = RaspberryJuicePlugin
				.makeRequest(new URI(RaspberryJuicePlugin.backendUrl + "/home/all"));

		JSONArray homes = (JSONArray) parser.parse(homeAreaString);
		for (int i = 0; i < homes.size(); i++) {
			JSONObject homeObject = (JSONObject) homes.get(i);
			String homeName = (String) homeObject.get("name");

			if (homeName == null) {
				continue;
			}

			Region region = Region.fromJson((JSONObject) homeObject.get("region"));
			registeredHomes.put(homeName, region);

			String ownerName = (String) homeObject.get("owner");
			if (ownerName != null) {
				if (isPlayerRegistered(ownerName)) {
					registeredPlayers.get(ownerName).fetchHomeInfo(homeName);
				}
			}

			plugin.getLogger().info("Updated home area " + homeName);
		}
	}

	public static void refreshHomeArea(String homeName, RaspberryJuicePlugin plugin) throws Exception {
		String homeString = RaspberryJuicePlugin
				.makeRequest(new URI(RaspberryJuicePlugin.backendUrl + "/home/get/" + homeName));
		JSONObject homeObject = (JSONObject) parser.parse(homeString);

		Region region = Region.fromJson((JSONObject) homeObject.get("region"));
		registeredHomes.put(homeName, region);

		String ownerName = (String) homeObject.get("owner");
		if (ownerName != null) {
			if (isPlayerRegistered(ownerName)) {
				registeredPlayers.get(ownerName).fetchHomeInfo(homeName);
			}
		}

		plugin.getLogger().info("Updated home area " + homeName);
	}

	public static void refreshRegisteredPlayers(RaspberryJuicePlugin plugin) throws Exception {
		plugin.getLogger().info("Refreshing all registered players...");

		registeredPlayers.clear();

		String allUserString = RaspberryJuicePlugin
				.makeRequest(new URI(RaspberryJuicePlugin.backendUrl + "/user/all"));

		JSONArray users = (JSONArray) parser.parse(allUserString);
		for (int i = 0; i < users.size(); i++) {
			JSONObject userObject = (JSONObject) users.get(i);
			String playerName = (String) userObject.get("name");

			if (playerName == null) {
				continue;
			}

			String homeName = (String) userObject.get("home");
			registeredPlayers.put(playerName, new PlayerBackend(playerName, homeName));

			plugin.getLogger().info("Updated registered player " + playerName);
		}
	}

	public static void refreshRegisteredPlayer(String userName, RaspberryJuicePlugin plugin) throws Exception {
		String userString = RaspberryJuicePlugin
				.makeRequest(new URI(RaspberryJuicePlugin.backendUrl + "/user/get/" + userName));
		JSONObject userObject = (JSONObject) parser.parse(userString);
		String homeName = (String) userObject.get("home");

		registeredPlayers.put(userName, new PlayerBackend(userName, homeName));

		plugin.getLogger().info("Updated registered player " + userName);
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

		backendSocket.on("refreshRestrictedAreas", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Socket event received: refreshRestrictedAreas");
				try {
					PlayerBackend.refreshRestrictedAreas(instance);
				} catch (Exception e) {
					System.out.println("Socket event failed: refreshRestrictedAreas");
					e.printStackTrace();
				}
			}
		});

		backendSocket.on("refreshHomeAreas", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Socket event received: refreshHomeAreas");
				try {
					PlayerBackend.refreshHomeAreas(instance);
				} catch (Exception e) {
					System.out.println("Socket event failed: refreshHomeAreas");
					e.printStackTrace();
				}
			}
		});

		backendSocket.on("refreshRegisteredPlayers", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Socket event received: refreshRegisteredPlayers");
				try {
					PlayerBackend.refreshRegisteredPlayers(instance);
				} catch (Exception e) {
					System.out.println("Socket event failed: refreshRegisteredPlayers");
					e.printStackTrace();
				}
			}
		});

		backendSocket.on("refreshHomeArea", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Socket event received: refreshHomeArea");
				try {
					PlayerBackend.refreshHomeArea((String) args[0], instance);
				} catch (Exception e) {
					System.out.println("Socket event failed: refreshHomeArea");
					e.printStackTrace();
				}
			}
		});

		backendSocket.on("refreshRegisteredPlayer", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Socket event received: refreshRegisteredPlayer");
				try {
					PlayerBackend.refreshRegisteredPlayer((String) args[0], instance);
				} catch (Exception e) {
					System.out.println("Socket event failed: refreshRegisteredPlayer");
					e.printStackTrace();
				}
			}
		});

		backendSocket.connect();
	}
}
