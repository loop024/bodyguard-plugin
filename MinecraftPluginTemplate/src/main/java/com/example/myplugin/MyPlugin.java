package com.example.myplugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class MyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("MyPluginを有効にしました。");
    }

    @Override
    public void onDisable() {
        getLogger().info("MyPluginを無効にしました。");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("myplugin")) {
            return false;
        }
        sender.sendMessage("§aMyPluginは正常に動作しています。");
        return true;
    }
}

