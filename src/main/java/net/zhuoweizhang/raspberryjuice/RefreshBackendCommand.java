package net.zhuoweizhang.raspberryjuice;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandExecutor;

public class RefreshBackendCommand implements CommandExecutor {

    RaspberryJuicePlugin plugin;

    public RefreshBackendCommand(RaspberryJuicePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (!player.isOp()) {
                return true;
            }

            try {
                PlayerBackend.refreshAll(this.plugin);
            } catch (Exception e) {
                this.plugin.getLogger().warning("Failed to refresh backend");
                this.plugin.getLogger().warning(e.getMessage());
                return false;
            }
        }

        return true;
    }
}
