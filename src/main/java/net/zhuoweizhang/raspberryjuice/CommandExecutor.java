package net.zhuoweizhang.raspberryjuice;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public class CommandExecutor {

	protected final LocationType locationType;

	protected Location origin;

	protected ArrayDeque<AsyncPlayerChatEvent> inQueue = new ArrayDeque<AsyncPlayerChatEvent>();

	protected ArrayDeque<String> outQueue = new ArrayDeque<String>();

	public final RaspberryJuicePlugin plugin;

	protected ArrayDeque<PlayerInteractEvent> interactEventQueue = new ArrayDeque<PlayerInteractEvent>();

	protected ArrayDeque<AsyncPlayerChatEvent> chatPostedQueue = new ArrayDeque<AsyncPlayerChatEvent>();

	protected ArrayDeque<ProjectileHitEvent> projectileHitQueue = new ArrayDeque<ProjectileHitEvent>();

	protected int maxCommandsPerTick = 1000;

	public static HashMap<Player, PlayerBackend> registeredPlayers = new HashMap<Player, PlayerBackend>();

	public CommandExecutor(RaspberryJuicePlugin plugin) {
		this.plugin = plugin;
		this.locationType = plugin.getLocationType();
	}

	public final Location getOrigin() {
		return origin;
	}

	public final void setOrigin(Location origin) {
		this.origin = origin;
	}

	public final void queuePlayerInteractEvent(PlayerInteractEvent event) {
		// plugin.getLogger().info(event.toString());
		interactEventQueue.add(event);
	}

	public final void queueChatPostedEvent(AsyncPlayerChatEvent event) {
		// plugin.getLogger().info(event.toString());
		chatPostedQueue.add(event);
	}

	public final void enqueueEventCommand(AsyncPlayerChatEvent event) {
		inQueue.add(event);
	}

	private void tryRunCommand(AsyncPlayerChatEvent event) {
		String chatMessage = event.getMessage();

		plugin.getLogger().info("Parsing chat command: " + chatMessage);

		chatMessage = chatMessage.replaceAll("\\s+", "");
		int leftBracket = chatMessage.indexOf('(');
		if (leftBracket == -1) {
			return;
		}
		String functionName = chatMessage.substring(0, leftBracket);
		String[] args = chatMessage.substring(leftBracket + 1, chatMessage.length() - 1).split(",");

		plugin.getLogger().info("Chat command: " + chatMessage + "(" + args + ")");

		String translatedFunctionName;

		switch (functionName) {
			case ("setBlock"): {
				translatedFunctionName = "world.setBlock";
				break;
			}
			case ("removeBlock"): {
				translatedFunctionName = "world.setBlock";
				args = new String[] { args[0], args[1], args[2], "" + Material.AIR.getId() };
				break;
			}
			case ("setPos"): {
				translatedFunctionName = "player.setPos";
				break;
			}
			case ("goHome"): {
				translatedFunctionName = "player.goHome";
				break;
			}
			default: {
				plugin.getLogger().warning("Unsupported command: " + functionName);
				return;
			}
		}

		String translatedFunctionCall = translatedFunctionName + "(" + String.join(",", args) + ")";
		plugin.getLogger().info("Translated function call: " + translatedFunctionCall);

		handleCommand(translatedFunctionName, args, event.getPlayer());
	}

	public final void queueProjectileHitEvent(ProjectileHitEvent event) {
		// plugin.getLogger().info(event.toString());

		if (event.getEntityType() == EntityType.ARROW) {
			Arrow arrow = (Arrow) event.getEntity();
			if (arrow.getShooter() instanceof Player) {
				projectileHitQueue.add(event);
			}
		}
	}

	/** called from the server main thread */
	public void tick() {
		if (origin == null) {
			switch (locationType) {
				case ABSOLUTE:
					this.origin = new Location(plugin.getServer().getWorlds().get(0), 0, 0, 0);
					break;
				case RELATIVE:
					this.origin = plugin.getServer().getWorlds().get(0).getSpawnLocation();
					break;
				default:
					throw new IllegalArgumentException("Unknown location type " + locationType);
			}
		}
		int processedCount = 0;
		AsyncPlayerChatEvent event;
		while ((event = inQueue.poll()) != null) {
			tryRunCommand(event);
			processedCount++;
			if (processedCount >= maxCommandsPerTick) {
				plugin.getLogger().warning("Over " + maxCommandsPerTick +
						" commands were queued - deferring " + inQueue.size() + " to next tick");
				break;
			}
		}
	}

	private void handleCommand(String c, String[] args, Player currentPlayer) {

		try {
			// get the server
			Server server = plugin.getServer();

			// get the world
			World world = origin.getWorld();

			PlayerBackend backend = registeredPlayers.get(currentPlayer);

			if (c.equals("world.setBlock")) {
				Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
				updateBlock(world, loc, Integer.parseInt(args[3]),
						(args.length > 4 ? Byte.parseByte(args[4]) : (byte) 0), currentPlayer);

			} else if (c.equals("player.setPos")) {
				String x = args[0], y = args[1], z = args[2];
				Location loc = currentPlayer.getLocation();
				currentPlayer.teleport(parseRelativeLocation(x, y, z, loc.getPitch(), loc.getYaw()));

			} else if (c.equals("player.goHome")) {
				currentPlayer.teleport(backend.getHome().getCenter(world));

				// not a command which is supported
			} else {
				plugin.getLogger().warning(c + " is not supported.");
			}

		} catch (Exception e) {

			plugin.getLogger().warning("Error occured handling command");
			e.printStackTrace();

		}
	}

	// create a cuboid of lots of blocks
	protected final void setCuboid(Location pos1, Location pos2, int blockType, byte data, Player player) {
		if (!registeredPlayers.containsKey(player)) {
			return;
		}

		PlayerBackend backend = registeredPlayers.get(player);
		if (!backend.isModificationAllowed(pos1) || !backend.isModificationAllowed(pos2)) {
			player.sendMessage("You are not allowed to build here!");;
			return;
		}

		int minX, maxX, minY, maxY, minZ, maxZ;
		World world = pos1.getWorld();
		minX = pos1.getBlockX() < pos2.getBlockX() ? pos1.getBlockX() : pos2.getBlockX();
		maxX = pos1.getBlockX() >= pos2.getBlockX() ? pos1.getBlockX() : pos2.getBlockX();
		minY = pos1.getBlockY() < pos2.getBlockY() ? pos1.getBlockY() : pos2.getBlockY();
		maxY = pos1.getBlockY() >= pos2.getBlockY() ? pos1.getBlockY() : pos2.getBlockY();
		minZ = pos1.getBlockZ() < pos2.getBlockZ() ? pos1.getBlockZ() : pos2.getBlockZ();
		maxZ = pos1.getBlockZ() >= pos2.getBlockZ() ? pos1.getBlockZ() : pos2.getBlockZ();

		for (int x = minX; x <= maxX; ++x) {
			for (int z = minZ; z <= maxZ; ++z) {
				for (int y = minY; y <= maxY; ++y) {
					Block thisBlock = world.getBlockAt(x, y, z);
					backend.updateBlock(thisBlock, blockType, data);
				}
			}
		}
	}

	// get a cuboid of lots of blocks
	protected final String getBlocks(Location pos1, Location pos2) {
		StringBuilder blockData = new StringBuilder();

		int minX, maxX, minY, maxY, minZ, maxZ;
		World world = pos1.getWorld();
		minX = pos1.getBlockX() < pos2.getBlockX() ? pos1.getBlockX() : pos2.getBlockX();
		maxX = pos1.getBlockX() >= pos2.getBlockX() ? pos1.getBlockX() : pos2.getBlockX();
		minY = pos1.getBlockY() < pos2.getBlockY() ? pos1.getBlockY() : pos2.getBlockY();
		maxY = pos1.getBlockY() >= pos2.getBlockY() ? pos1.getBlockY() : pos2.getBlockY();
		minZ = pos1.getBlockZ() < pos2.getBlockZ() ? pos1.getBlockZ() : pos2.getBlockZ();
		maxZ = pos1.getBlockZ() >= pos2.getBlockZ() ? pos1.getBlockZ() : pos2.getBlockZ();

		for (int y = minY; y <= maxY; ++y) {
			for (int x = minX; x <= maxX; ++x) {
				for (int z = minZ; z <= maxZ; ++z) {
					blockData.append(new Integer(world.getBlockTypeIdAt(x, y, z)).toString() + ",");
				}
			}
		}

		return blockData.substring(0, blockData.length() > 0 ? blockData.length() - 1 : 0); // We don't want last comma
	}

	// updates a block
	protected final void updateBlock(World world, Location loc, int blockType, byte blockData, Player player) {
		if (registeredPlayers.containsKey(player)) {
			PlayerBackend backend = registeredPlayers.get(player);
			if (!backend.isModificationAllowed(loc)) {
				player.sendMessage("You are not allowed to build here!");;
				return;
			}

			Block thisBlock = world.getBlockAt(loc);
			backend.updateBlock(thisBlock, blockType, blockData);
		}
	}

	public final Location parseRelativeBlockLocation(String xstr, String ystr, String zstr) {
		int x = (int) Double.parseDouble(xstr);
		int y = (int) Double.parseDouble(ystr);
		int z = (int) Double.parseDouble(zstr);
		return parseLocation(origin.getWorld(), x, y, z, origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
	}

	public final Location parseRelativeLocation(String xstr, String ystr, String zstr) {
		double x = Double.parseDouble(xstr);
		double y = Double.parseDouble(ystr);
		double z = Double.parseDouble(zstr);
		return parseLocation(origin.getWorld(), x, y, z, origin.getX(), origin.getY(), origin.getZ());
	}

	public final Location parseRelativeBlockLocation(String xstr, String ystr, String zstr, float pitch, float yaw) {
		Location loc = parseRelativeBlockLocation(xstr, ystr, zstr);
		loc.setPitch(pitch);
		loc.setYaw(yaw);
		return loc;
	}

	public final Location parseRelativeLocation(String xstr, String ystr, String zstr, float pitch, float yaw) {
		Location loc = parseRelativeLocation(xstr, ystr, zstr);
		loc.setPitch(pitch);
		loc.setYaw(yaw);
		return loc;
	}

	public final String blockLocationToRelative(Location loc) {
		return parseLocation(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), origin.getBlockX(), origin.getBlockY(),
				origin.getBlockZ());
	}

	public final String locationToRelative(Location loc) {
		return parseLocation(loc.getX(), loc.getY(), loc.getZ(), origin.getX(), origin.getY(), origin.getZ());
	}

	protected final String parseLocation(double x, double y, double z, double originX, double originY, double originZ) {
		return (x - originX) + "," + (y - originY) + "," + (z - originZ);
	}

	protected final Location parseLocation(World world, double x, double y, double z, double originX, double originY,
			double originZ) {
		return new Location(world, originX + x, originY + y, originZ + z);
	}

	protected final String parseLocation(int x, int y, int z, int originX, int originY, int originZ) {
		return (x - originX) + "," + (y - originY) + "," + (z - originZ);
	}

	protected final Location parseLocation(World world, int x, int y, int z, int originX, int originY, int originZ) {
		return new Location(world, originX + x, originY + y, originZ + z);
	}

	protected final double getDistance(Entity ent1, Entity ent2) {
		if (ent1 == null || ent2 == null)
			return -1;
		double dx = ent2.getLocation().getX() - ent1.getLocation().getX();
		double dy = ent2.getLocation().getY() - ent1.getLocation().getY();
		double dz = ent2.getLocation().getZ() - ent1.getLocation().getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	protected final String getEntities(World world, int entityType) {
		StringBuilder bdr = new StringBuilder();
		for (Entity e : world.getEntities()) {
			if (((entityType == -1 && e.getType().getTypeId() >= 0) || e.getType().getTypeId() == entityType) &&
					e.getType().isSpawnable()) {
				bdr.append(getEntityMsg(e));
			}
		}
		return bdr.toString();
	}

	protected final String getEntities(World world, int entityId, int distance, int entityType) {
		Entity playerEntity = plugin.getEntity(entityId);
		StringBuilder bdr = new StringBuilder();
		for (Entity e : world.getEntities()) {
			if (((entityType == -1 && e.getType().getTypeId() >= 0) || e.getType().getTypeId() == entityType) &&
					e.getType().isSpawnable() &&
					getDistance(playerEntity, e) <= distance) {
				bdr.append(getEntityMsg(e));
			}
		}
		return bdr.toString();
	}

	protected final String getEntityMsg(Entity entity) {
		StringBuilder bdr = new StringBuilder();
		bdr.append(entity.getEntityId());
		bdr.append(",");
		bdr.append(entity.getType().getTypeId());
		bdr.append(",");
		bdr.append(entity.getType().toString());
		bdr.append(",");
		bdr.append(entity.getLocation().getX());
		bdr.append(",");
		bdr.append(entity.getLocation().getY());
		bdr.append(",");
		bdr.append(entity.getLocation().getZ());
		bdr.append("|");
		return bdr.toString();
	}

	protected final int removeEntities(World world, int entityId, int distance, int entityType) {
		int removedEntitiesCount = 0;
		Entity playerEntityId = plugin.getEntity(entityId);
		for (Entity e : world.getEntities()) {
			if ((entityType == -1 || e.getType().getTypeId() == entityType)
					&& getDistance(playerEntityId, e) <= distance) {
				e.remove();
				removedEntitiesCount++;
			}
		}
		return removedEntitiesCount;
	}

	protected final String getBlockHits() {
		return getBlockHits(-1);
	}

	protected final String getBlockHits(int entityId) {
		StringBuilder b = new StringBuilder();
		for (Iterator<PlayerInteractEvent> iter = interactEventQueue.iterator(); iter.hasNext();) {
			PlayerInteractEvent event = iter.next();
			if (entityId == -1 || event.getPlayer().getEntityId() == entityId) {
				Block block = event.getClickedBlock();
				Location loc = block.getLocation();
				b.append(blockLocationToRelative(loc));
				b.append(",");
				b.append(blockFaceToNotch(event.getBlockFace()));
				b.append(",");
				b.append(event.getPlayer().getEntityId());
				b.append("|");
				iter.remove();
			}
		}
		if (b.length() > 0)
			b.deleteCharAt(b.length() - 1);

		return b.toString();
	}

	protected final String getChatPosts() {
		return getChatPosts(-1);
	}

	protected final String getChatPosts(int entityId) {
		StringBuilder b = new StringBuilder();
		for (Iterator<AsyncPlayerChatEvent> iter = chatPostedQueue.iterator(); iter.hasNext();) {
			AsyncPlayerChatEvent event = iter.next();
			if (entityId == -1 || event.getPlayer().getEntityId() == entityId) {
				b.append(event.getPlayer().getEntityId());
				b.append(",");
				b.append(event.getMessage());
				b.append("|");
				iter.remove();
			}
		}
		if (b.length() > 0)
			b.deleteCharAt(b.length() - 1);
		return b.toString();
	}

	protected final String getProjectileHits() {
		return getProjectileHits(-1);
	}

	protected final String getProjectileHits(int entityId) {
		StringBuilder b = new StringBuilder();
		for (Iterator<ProjectileHitEvent> iter = projectileHitQueue.iterator(); iter.hasNext();) {
			ProjectileHitEvent event = iter.next();
			Arrow arrow = (Arrow) event.getEntity();
			LivingEntity shooter = (LivingEntity) arrow.getShooter();
			if (entityId == -1 || shooter.getEntityId() == entityId) {
				if (shooter instanceof Player) {
					Player player = (Player) shooter;
					Block block = arrow.getAttachedBlock();
					if (block == null)
						block = arrow.getLocation().getBlock();
					Location loc = block.getLocation();
					b.append(blockLocationToRelative(loc));
					b.append(",");
					b.append(1); // blockFaceToNotch(event.getBlockFace()), but don't really care
					b.append(",");
					b.append(player.getPlayerListName());
					b.append(",");
					Entity hitEntity = event.getHitEntity();
					if (hitEntity != null) {
						if (hitEntity instanceof Player) {
							Player hitPlayer = (Player) hitEntity;
							b.append(hitPlayer.getPlayerListName());
						} else {
							b.append(hitEntity.getName());
						}
					}
				}
				b.append("|");
				arrow.remove();
				iter.remove();
			}
		}
		if (b.length() > 0)
			b.deleteCharAt(b.length() - 1);
		return b.toString();

	}

	protected final void clearEntityEvents(int entityId) {
		for (Iterator<PlayerInteractEvent> iter = interactEventQueue.iterator(); iter.hasNext();) {
			PlayerInteractEvent event = iter.next();
			if (event.getPlayer().getEntityId() == entityId)
				iter.remove();
		}
		for (Iterator<AsyncPlayerChatEvent> iter = chatPostedQueue.iterator(); iter.hasNext();) {
			AsyncPlayerChatEvent event = iter.next();
			if (event.getPlayer().getEntityId() == entityId)
				iter.remove();
		}
		for (Iterator<ProjectileHitEvent> iter = projectileHitQueue.iterator(); iter.hasNext();) {
			ProjectileHitEvent event = iter.next();
			Arrow arrow = (Arrow) event.getEntity();
			LivingEntity shooter = (LivingEntity) arrow.getShooter();
			if (shooter.getEntityId() == entityId)
				iter.remove();
		}
	}

	/**
	 * from CraftBukkit's org.bukkit.craftbukkit.block.CraftBlock.blockFactToNotch
	 */
	public static int blockFaceToNotch(BlockFace face) {
		switch (face) {
			case DOWN:
				return 0;
			case UP:
				return 1;
			case NORTH:
				return 2;
			case SOUTH:
				return 3;
			case WEST:
				return 4;
			case EAST:
				return 5;
			default:
				return 7; // Good as anything here, but technically invalid
		}
	}

}
