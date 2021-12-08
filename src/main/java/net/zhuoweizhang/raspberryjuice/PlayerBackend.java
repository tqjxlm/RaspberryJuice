package net.zhuoweizhang.raspberryjuice;

import java.net.*;
import java.util.ArrayList;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;

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
};

public class PlayerBackend {

	public Region homeArea;

	public static ArrayList<Region> restrictedAreas = new ArrayList<Region>();

	public static boolean valid = false;

	public static JSONParser parser = new JSONParser();

	public PlayerBackend(String playerName) throws Exception {

		String userString = RaspberryJuicePlugin
				.makeRequest(new URI(RaspberryJuicePlugin.backendUrl + "/user/get/" + playerName));
		JSONObject user = (JSONObject) parser.parse(userString);

		if (user.containsKey("home")) {
			String homeName = (String) user.get("home");
			String homeString = RaspberryJuicePlugin
					.makeRequest(new URI(RaspberryJuicePlugin.backendUrl + "/home/get/" + homeName));

			JSONObject home = (JSONObject) parser.parse(homeString);

			JSONObject region = (JSONObject) home.get("region");
			Vector3 min = new Vector3((JSONObject) region.get("min"));
			Vector3 max = new Vector3((JSONObject) region.get("max"));
			Vector3 center = new Vector3((JSONObject) region.get("center"));
			this.homeArea = new Region(min, max, center);
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

	public static void retrieveRestrictedAreas() throws Exception {
		String restrictedAreaString = RaspberryJuicePlugin
				.makeRequest(new URI(RaspberryJuicePlugin.backendUrl + "/restricted_area/all"));

		JSONArray areas = (JSONArray) parser.parse(restrictedAreaString);
		for (int i = 0; i < areas.size(); i++) {
			JSONObject area = (JSONObject) areas.get(i);

			JSONObject region = (JSONObject) area.get("region");
			Vector3 min = new Vector3((JSONObject) region.get("min"));
			Vector3 max = new Vector3((JSONObject) region.get("max"));
			Vector3 center = new Vector3((JSONObject) region.get("center"));
			restrictedAreas.add(new Region(min, max, center));
		}
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
}
