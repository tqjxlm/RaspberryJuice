package net.zhuoweizhang.raspberryjuice;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.block.Sign;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

@SuppressWarnings("deprecation")
public class RemoteSession extends CommandSession {

	private Socket socket;

	private BufferedReader in;

	private BufferedWriter out;

	private Thread inThread;

	private Thread outThread;

	public boolean running = true;

	public boolean pendingRemoval = false;

	private boolean closed = false;

	private Player attachedPlayer = null;

	protected ArrayDeque<String> inQueue = new ArrayDeque<String>();

	protected ArrayDeque<String> outQueue = new ArrayDeque<String>();

	public RemoteSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException {
		super(plugin);
		this.socket = socket;
		init();
	}

	public void init() throws IOException {
		socket.setTcpNoDelay(true);
		socket.setKeepAlive(true);
		socket.setTrafficClass(0x10);
		this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
		this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
		startThreads();
		plugin.getLogger().info("Opened connection to" + socket.getRemoteSocketAddress() + ".");
	}

	protected void startThreads() {
		inThread = new Thread(new InputThread());
		inThread.start();
		outThread = new Thread(new OutputThread());
		outThread.start();
	}

	public Socket getSocket() {
		return socket;
	}

	protected void handleCommand(String c, String[] args) {

		try {
			// get the server
			Server server = plugin.getServer();

			// get the world
			World world = origin.getWorld();

			// get current player
			Player currentPlayer = getCurrentPlayer();

			// world.getBlock
			if (c.equals("world.getBlock")) {
				Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
				send(world.getBlockTypeIdAt(loc));

				// world.getBlocks
			} else if (c.equals("world.getBlocks")) {
				Location loc1 = parseRelativeBlockLocation(args[0], args[1], args[2]);
				Location loc2 = parseRelativeBlockLocation(args[3], args[4], args[5]);
				send(getBlocks(loc1, loc2));

				// world.getBlockWithData
			} else if (c.equals("world.getBlockWithData")) {
				Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
				send(world.getBlockTypeIdAt(loc) + "," + world.getBlockAt(loc).getData());

				// world.setBlock
			} else if (c.equals("world.setBlock")) {
				Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
				updateBlock(world, loc, Integer.parseInt(args[3]),
						(args.length > 4 ? Byte.parseByte(args[4]) : (byte) 0), currentPlayer);

				// world.setBlocks
			} else if (c.equals("world.setBlocks")) {
				Location loc1 = parseRelativeBlockLocation(args[0], args[1], args[2]);
				Location loc2 = parseRelativeBlockLocation(args[3], args[4], args[5]);
				int blockType = Integer.parseInt(args[6]);
				byte data = args.length > 7 ? Byte.parseByte(args[7]) : (byte) 0;
				setCuboid(loc1, loc2, blockType, data, currentPlayer);

				// world.getPlayerIds
			} else if (c.equals("world.getPlayerIds")) {
				StringBuilder bdr = new StringBuilder();
				Collection<? extends Player> players = Bukkit.getOnlinePlayers();
				if (players.size() > 0) {
					for (Player p : players) {
						bdr.append(p.getEntityId());
						bdr.append("|");
					}
					bdr.deleteCharAt(bdr.length() - 1);
					send(bdr.toString());
				} else {
					send("Fail");
				}

				// world.getPlayerId
			} else if (c.equals("world.getPlayerId")) {
				Player p = plugin.getNamedPlayer(args[0]);
				if (p != null) {
					send(p.getEntityId());
				} else {
					plugin.getLogger().info("Player [" + args[0] + "] not found.");
					send("Fail");
				}

				// entity.getListName
			} else if (c.equals("entity.getName")) {
				Entity e = plugin.getEntity(Integer.parseInt(args[0]));
				if (e == null) {
					plugin.getLogger().info("Player (or Entity) [" + args[0] + "] not found in entity.getName.");
				} else if (e instanceof Player) {
					Player p = (Player) e;
					// sending list name because plugin.getNamedPlayer() uses list name
					send(p.getPlayerListName());
				} else if (e != null) {
					send(e.getName());
				}

				// world.getEntities
			} else if (c.equals("world.getEntities")) {
				int entityType = Integer.parseInt(args[0]);
				send(getEntities(world, entityType));

				// world.removeEntity
			} else if (c.equals("world.removeEntity")) {
				int result = 0;
				for (Entity e : world.getEntities()) {
					if (e.getEntityId() == Integer.parseInt(args[0])) {
						e.remove();
						result = 1;
						break;
					}
				}
				send(result);

				// world.removeEntities
			} else if (c.equals("world.removeEntities")) {
				int entityType = Integer.parseInt(args[0]);
				int removedEntitiesCount = 0;
				for (Entity e : world.getEntities()) {
					if (entityType == -1 || e.getType().getTypeId() == entityType) {
						e.remove();
						removedEntitiesCount++;
					}
				}
				send(removedEntitiesCount);

				// chat.post
			} else if (c.equals("chat.post")) {
				// create chat message from args as it was split by ,
				String chatMessage = "";
				int count;
				for (count = 0; count < args.length; count++) {
					chatMessage = chatMessage + args[count] + ",";
				}
				chatMessage = chatMessage.substring(0, chatMessage.length() - 1);
				server.broadcastMessage(chatMessage);

				// events.clear
			} else if (c.equals("events.clear")) {
				interactEventQueue.clear();
				chatPostedQueue.clear();

				// events.block.hits
			} else if (c.equals("events.block.hits")) {
				send(getBlockHits());

				// events.chat.posts
			} else if (c.equals("events.chat.posts")) {
				send(getChatPosts());

				// events.projectile.hits
			} else if (c.equals("events.projectile.hits")) {
				send(getProjectileHits());

				// entity.events.clear
			} else if (c.equals("entity.events.clear")) {
				int entityId = Integer.parseInt(args[0]);
				clearEntityEvents(entityId);

				// entity.events.block.hits
			} else if (c.equals("entity.events.block.hits")) {
				int entityId = Integer.parseInt(args[0]);
				send(getBlockHits(entityId));

				// entity.events.chat.posts
			} else if (c.equals("entity.events.chat.posts")) {
				int entityId = Integer.parseInt(args[0]);
				send(getChatPosts(entityId));

				// entity.events.projectile.hits
			} else if (c.equals("entity.events.projectile.hits")) {
				int entityId = Integer.parseInt(args[0]);
				send(getProjectileHits(entityId));

				// player.getTile
			} else if (c.equals("player.getTile")) {
				send(blockLocationToRelative(currentPlayer.getLocation()));

				// player.setTile
			} else if (c.equals("player.setTile")) {
				String x = args[0], y = args[1], z = args[2];
				// get players current location, so when they are moved we will use the same
				// pitch and yaw (rotation)
				Location loc = currentPlayer.getLocation();
				currentPlayer.teleport(parseRelativeBlockLocation(x, y, z, loc.getPitch(), loc.getYaw()));

				// player.getAbsPos
			} else if (c.equals("player.getAbsPos")) {
				send(currentPlayer.getLocation());

				// player.setAbsPos
			} else if (c.equals("player.setAbsPos")) {
				String x = args[0], y = args[1], z = args[2];
				// get players current location, so when they are moved we will use the same
				// pitch and yaw (rotation)
				Location loc = currentPlayer.getLocation();
				loc.setX(Double.parseDouble(x));
				loc.setY(Double.parseDouble(y));
				loc.setZ(Double.parseDouble(z));
				currentPlayer.teleport(loc);

				// player.getPos
			} else if (c.equals("player.getPos")) {
				send(locationToRelative(currentPlayer.getLocation()));

				// player.setPos
			} else if (c.equals("player.setPos")) {
				String x = args[0], y = args[1], z = args[2];
				// get players current location, so when they are moved we will use the same
				// pitch and yaw (rotation)
				Location loc = currentPlayer.getLocation();
				currentPlayer.teleport(parseRelativeLocation(x, y, z, loc.getPitch(), loc.getYaw()));

				// player.setDirection
			} else if (c.equals("player.setDirection")) {
				Double x = Double.parseDouble(args[0]);
				Double y = Double.parseDouble(args[1]);
				Double z = Double.parseDouble(args[2]);
				Location loc = currentPlayer.getLocation();
				loc.setDirection(new Vector(x, y, z));
				currentPlayer.teleport(loc);

				// player.getDirection
			} else if (c.equals("player.getDirection")) {
				send(currentPlayer.getLocation().getDirection().toString());

				// player.setRotation
			} else if (c.equals("player.setRotation")) {
				Float yaw = Float.parseFloat(args[0]);
				Location loc = currentPlayer.getLocation();
				loc.setYaw(yaw);
				currentPlayer.teleport(loc);

				// player.getTargetedBlock
			} else if (c.equals("player.getTargetedBlock")) {
				Location location = currentPlayer.getTargetBlock((Set<Material>) null, 20).getLocation();
				send(locationToRelative(location));

				// player.getRotation
			} else if (c.equals("player.getRotation")) {
				float yaw = currentPlayer.getLocation().getYaw();
				// turn bukkit's 0 - -360 to positive numbers
				if (yaw < 0)
					yaw = yaw * -1;
				send(yaw);

				// player.setPitch
			} else if (c.equals("player.setPitch")) {
				Float pitch = Float.parseFloat(args[0]);
				Location loc = currentPlayer.getLocation();
				loc.setPitch(pitch);
				currentPlayer.teleport(loc);

				// player.getPitch
			} else if (c.equals("player.getPitch")) {
				send(currentPlayer.getLocation().getPitch());

				// player.getEntities
			} else if (c.equals("player.getEntities")) {
				int distance = Integer.parseInt(args[0]);
				int entityTypeId = Integer.parseInt(args[1]);

				send(getEntities(world, currentPlayer.getEntityId(), distance, entityTypeId));

				// player.removeEntities
			} else if (c.equals("player.removeEntities")) {
				int distance = Integer.parseInt(args[0]);
				int entityType = Integer.parseInt(args[1]);

				send(removeEntities(world, currentPlayer.getEntityId(), distance, entityType));

				// player.events.block.hits
			} else if (c.equals("player.events.block.hits")) {
				send(getBlockHits(currentPlayer.getEntityId()));

				// player.events.chat.posts
			} else if (c.equals("player.events.chat.posts")) {
				send(getChatPosts(currentPlayer.getEntityId()));

				// player.events.projectile.hits
			} else if (c.equals("player.events.projectile.hits")) {
				send(getProjectileHits(currentPlayer.getEntityId()));

				// player.events.clear
			} else if (c.equals("player.events.clear")) {
				clearEntityEvents(currentPlayer.getEntityId());

				// world.getHeight
			} else if (c.equals("world.getHeight")) {
				send(world.getHighestBlockYAt(parseRelativeBlockLocation(args[0], "0", args[1])) - origin.getBlockY());

				// entity.getTile
			} else if (c.equals("entity.getTile")) {
				// get entity based on id
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					send(blockLocationToRelative(entity.getLocation()));
				} else {
					plugin.getLogger().info("Entity [" + args[0] + "] not found.");
					send("Fail");
				}

				// entity.setTile
			} else if (c.equals("entity.setTile")) {
				String x = args[1], y = args[2], z = args[3];
				// get entity based on id
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					// get entity's current location, so when they are moved we will use the same
					// pitch and yaw (rotation)
					Location loc = entity.getLocation();
					entity.teleport(parseRelativeBlockLocation(x, y, z, loc.getPitch(), loc.getYaw()));
				} else {
					plugin.getLogger().info("Entity [" + args[0] + "] not found.");
					send("Fail");
				}

				// entity.getPos
			} else if (c.equals("entity.getPos")) {
				// get entity based on id
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				// Player entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					send(locationToRelative(entity.getLocation()));
				} else {
					plugin.getLogger().info("Entity [" + args[0] + "] not found.");
					send("Fail");
				}

				// entity.setPos
			} else if (c.equals("entity.setPos")) {
				String x = args[1], y = args[2], z = args[3];
				// get entity based on id
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					// get entity's current location, so when they are moved we will use the same
					// pitch and yaw (rotation)
					Location loc = entity.getLocation();
					entity.teleport(parseRelativeLocation(x, y, z, loc.getPitch(), loc.getYaw()));
				} else {
					plugin.getLogger().info("Entity [" + args[0] + "] not found.");
					send("Fail");
				}

				// entity.setDirection
			} else if (c.equals("entity.setDirection")) {
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					Double x = Double.parseDouble(args[1]);
					Double y = Double.parseDouble(args[2]);
					Double z = Double.parseDouble(args[3]);
					Location loc = entity.getLocation();
					loc.setDirection(new Vector(x, y, z));
					entity.teleport(loc);
				} else {
					plugin.getLogger().info("Entity [" + args[0] + "] not found.");
				}

				// entity.getDirection
			} else if (c.equals("entity.getDirection")) {
				// get entity based on id
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					send(entity.getLocation().getDirection().toString());
				} else {
					plugin.getLogger().info("Entity [" + args[0] + "] not found.");
					send("Fail");
				}

				// entity.setRotation
			} else if (c.equals("entity.setRotation")) {
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					Float yaw = Float.parseFloat(args[1]);
					Location loc = entity.getLocation();
					loc.setYaw(yaw);
					entity.teleport(loc);
				} else {
					plugin.getLogger().info("Entity [" + args[0] + "] not found.");
				}

				// entity.getRotation
			} else if (c.equals("entity.getRotation")) {
				// get entity based on id
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					send(entity.getLocation().getYaw());
				} else {
					plugin.getLogger().info("Entity [" + args[0] + "] not found.");
					send("Fail");
				}

				// entity.setPitch
			} else if (c.equals("entity.setPitch")) {
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					Float pitch = Float.parseFloat(args[1]);
					Location loc = entity.getLocation();
					loc.setPitch(pitch);
					entity.teleport(loc);
				} else {
					plugin.getLogger().info("Entity [" + args[0] + "] not found.");
				}

				// entity.getPitch
			} else if (c.equals("entity.getPitch")) {
				// get entity based on id
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					send(entity.getLocation().getPitch());
				} else {
					plugin.getLogger().info("Entity [" + args[0] + "] not found.");
					send("Fail");
				}

				// entity.getEntities
			} else if (c.equals("entity.getEntities")) {
				int entityId = Integer.parseInt(args[0]);
				int distance = Integer.parseInt(args[1]);
				int entityTypeId = Integer.parseInt(args[2]);

				send(getEntities(world, entityId, distance, entityTypeId));

				// entity.removeEntities
			} else if (c.equals("entity.removeEntities")) {
				int entityId = Integer.parseInt(args[0]);
				int distance = Integer.parseInt(args[1]);
				int entityType = Integer.parseInt(args[2]);

				send(removeEntities(world, entityId, distance, entityType));

				// world.setSign
			} else if (c.equals("world.setSign")) {
				Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
				Block thisBlock = world.getBlockAt(loc);
				// blockType should be 68 for wall sign or 63 for standing sign
				int blockType = Integer.parseInt(args[3]);
				// facing direction for wall sign : 2=north, 3=south, 4=west, 5=east
				// rotation 0 - to 15 for standing sign : 0=south, 4=west, 8=north, 12=east
				byte blockData = Byte.parseByte(args[4]);
				if ((thisBlock.getTypeId() != blockType) || (thisBlock.getData() != blockData)) {
					thisBlock.setTypeIdAndData(blockType, blockData, false);
				}
				// plugin.getLogger().info("Creating sign at " + loc);
				if (thisBlock.getState() instanceof Sign) {
					Sign sign = (Sign) thisBlock.getState();
					for (int i = 5; i - 5 < 4 && i < args.length; i++) {
						sign.setLine(i - 5, args[i]);
					}
					sign.update();
				}

				// world.spawnEntity
			} else if (c.equals("world.spawnEntity")) {
				Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
				Entity entity = world.spawnEntity(loc, EntityType.fromId(Integer.parseInt(args[3])));
				send(entity.getEntityId());

				// world.getEntityTypes
			} else if (c.equals("world.getEntityTypes")) {
				StringBuilder bdr = new StringBuilder();
				for (EntityType entityType : EntityType.values()) {
					if (entityType.isSpawnable() && entityType.getTypeId() >= 0) {
						bdr.append(entityType.getTypeId());
						bdr.append(",");
						bdr.append(entityType.toString());
						bdr.append("|");
					}
				}
				send(bdr.toString());

			} else if (c.equals("inventory.addItem")) {
				Inventory inventory = currentPlayer.getInventory();
				Integer typeId = Integer.parseInt(args[0]);
				Integer amount = Integer.parseInt(args[1]);
				ItemStack item = new ItemStack(Material.values()[typeId], amount);
				inventory.addItem(item);

			} else if (c.equals("inventory.removeItem")) {
				Inventory inventory = currentPlayer.getInventory();
				Integer typeId = Integer.parseInt(args[0]);
				Integer amount = Integer.parseInt(args[1]);
				ItemStack item = new ItemStack(Material.values()[typeId], amount);
				inventory.removeItem(item);

				// not a command which is supported
			} else {
				plugin.getLogger().warning(c + " is not supported.");
				send("Fail");
			}
		} catch (Exception e) {

			plugin.getLogger().warning("Error occurred handling command");
			e.printStackTrace();
			send("Fail");

		}
	}

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
		String message;
		while ((message = inQueue.poll()) != null) {
			handleLine(message);
			processedCount++;
			if (processedCount >= maxCommandsPerTick) {
				plugin.getLogger().warning("Over " + maxCommandsPerTick +
						" commands were queued - deferring " + inQueue.size() + " to next tick");
				break;
			}
		}
	}

	protected void handleLine(String line) {
		try {
			// System.out.println(line);
			String methodName = line.substring(0, line.indexOf("("));
			// split string into args, handles , inside " i.e. ","
			String[] args = line.substring(line.indexOf("(") + 1, line.length() - 1).split(",");

			// System.out.println(methodName + ":" + Arrays.toString(args));
			handleCommand(methodName, args);
		} catch (Exception e) {
			this.plugin.getLogger().warning("Failed to run command: " + line);
		}
	}

	// gets the current player
	public Player getCurrentPlayer() {
		Player player = attachedPlayer;
		// if the player hasn't already been retrieved for this session, go and get it.
		if (player == null) {
			player = plugin.getHostPlayer();
			attachedPlayer = player;
		}
		return player;
	}

	public Player getCurrentPlayer(String name) {
		// if a named player is returned use that
		Player player = plugin.getNamedPlayer(name);
		// otherwise if there is an attached player for this session use that
		if (player == null) {
			player = attachedPlayer;
			// otherwise go and get the host player and make that the attached player
			if (player == null) {
				player = plugin.getHostPlayer();
				attachedPlayer = player;
			}
		}
		return player;
	}

	public void send(Object a) {
		send(a.toString());
	}

	public void send(String a) {
		if (pendingRemoval)
			return;
		synchronized (outQueue) {
			outQueue.add(a);
		}
	}

	public void close() {
		if (closed)
			return;
		running = false;
		pendingRemoval = true;

		// wait for threads to stop
		try {
			inThread.join(2000);
			outThread.join(2000);
		} catch (InterruptedException e) {
			plugin.getLogger().warning("Failed to stop in/out thread");
			e.printStackTrace();
		}

		try {
			socket.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		plugin.getLogger().info("Closed connection to" + socket.getRemoteSocketAddress() + ".");
	}

	public void kick(String reason) {
		try {
			out.write(reason);
			out.flush();
		} catch (Exception e) {
		}
		close();
	}

	/** socket listening thread */
	private class InputThread implements Runnable {
		public void run() {
			plugin.getLogger().info("Starting input thread");
			while (running) {
				try {
					String newLine = in.readLine();
					// System.out.println(newLine);
					if (newLine == null) {
						running = false;
					} else {
						inQueue.add(newLine);
						// System.out.println("Added to in queue");
					}
				} catch (Exception e) {
					// if its running raise an error
					if (running) {
						if (e.getMessage().equals("Connection reset")) {
							plugin.getLogger().info("Connection reset");
						} else {
							e.printStackTrace();
						}
						running = false;
					}
				}
			}
			// close in buffer
			try {
				in.close();
			} catch (Exception e) {
				plugin.getLogger().warning("Failed to close in buffer");
				e.printStackTrace();
			}
		}
	}

	private class OutputThread implements Runnable {
		public void run() {
			plugin.getLogger().info("Starting output thread!");
			while (running) {
				try {
					String line;
					while ((line = outQueue.poll()) != null) {
						out.write(line);
						out.write('\n');
					}
					out.flush();
					Thread.yield();
					Thread.sleep(1L);
				} catch (Exception e) {
					// if its running raise an error
					if (running) {
						e.printStackTrace();
						running = false;
					}
				}
			}
			// close out buffer
			try {
				out.close();
			} catch (Exception e) {
				plugin.getLogger().warning("Failed to close out buffer");
				e.printStackTrace();
			}
		}
	}
}
