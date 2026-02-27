package my.pkg;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public interface SupplyItem {
    String id();
    ItemStack create(JavaPlugin plugin);

    default void onRightClick(JavaPlugin plugin, Player p, PlayerInteractEvent e) {}
    default void onHitEntity(JavaPlugin plugin, Player attacker, LivingEntity victim, EntityDamageByEntityEvent e) {}
    default void onProjectileHit(JavaPlugin plugin, ProjectileHitEvent e) {}
    default void onPlayerMove(JavaPlugin plugin, PlayerMoveEvent e) {}
}