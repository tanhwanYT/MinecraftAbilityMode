package my.pkg;

import java.util.*;

import my.pkg.abilities.Ability;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.NamespacedKey;

public class AbilitySystem implements Listener, CommandExecutor, org.bukkit.command.TabCompleter {
    private final JavaPlugin plugin;
    private final Map<String, Ability> registry = new HashMap<>();
    private final Map<UUID, PlayerState> states = new HashMap<>();

    private final GameManager gameManager;
    private final NamespacedKey trackerCompassKey;
    private final NamespacedKey rerollKey;

    private AbilityPickManager abilityPickManager;
    private BukkitTask trackerTask;

    private static final int NORMAL_LAPIS_COUNT = 16;
    private static final int NORMAL_XP_AMOUNT = 300;

    private static final int MINI_LAPIS_COUNT = 8;
    private static final int MINI_XP_AMOUNT = 120;

    public AbilitySystem(JavaPlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.rerollKey = new NamespacedKey(plugin, "reroll_ticket");
        this.trackerCompassKey = new NamespacedKey(plugin, "tracker_compass");
    }

    public void setAbilityPickManager(AbilityPickManager abilityPickManager) {
        this.abilityPickManager = abilityPickManager;
    }

    public Collection<Ability> getRegisteredAbilities() {
        return Collections.unmodifiableCollection(registry.values());
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public void register(Ability ability) {
        registry.put(ability.id().toLowerCase(), ability);

        if (ability instanceof Listener listener) {
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        }
    }

    public Ability getAbility(String id) {
        if (id == null) return null;
        return registry.get(id.toLowerCase());
    }

    public PlayerState getState(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), PlayerState::new);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("ability")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> subs = Arrays.asList(
                    "me",
                    "give",
                    "reroll",
                    "rerollall",
                    "start",
                    "startmini",
                    "startpick",
                    "rerollitem"
            );
            return partialMatch(args[0], subs);
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (args.length == 2) {
                List<String> names = new ArrayList<>();
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    names.add(p.getName());
                }
                return partialMatch(args[1], names);
            }

            if (args.length == 3) {
                return partialMatch(args[2], new ArrayList<>(registry.keySet()));
            }
        }

        if (args[0].equalsIgnoreCase("reroll")) {
            if (args.length == 2) {
                List<String> names = new ArrayList<>();
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    names.add(p.getName());
                }
                return partialMatch(args[1], names);
            }
        }

        if (args[0].equalsIgnoreCase("rerollitem")) {
            if (args.length == 2) {
                return partialMatch(args[1], Arrays.asList("1", "2", "3", "5", "10"));
            }
        }

        return Collections.emptyList();
    }

    private List<String> partialMatch(String input, Collection<String> candidates) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();

        for (String s : candidates) {
            if (s.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(s);
            }
        }

        Collections.sort(result);
        return result;
    }

    public void grant(Player player, Ability ability) {
        PlayerState state = getState(player);
        if (state.cooldownTask != null) {
            state.cooldownTask.cancel();
            state.cooldownTask = null;
        }
        player.sendActionBar("");

        if (state.ability != null) {
            state.ability.onRemove(this, player);
        }

        state.ability = ability;
        state.nextUsableAtMs = 0;

        if (ability != null) {
            ability.onGrant(this, player);
        }
    }

    private ItemStack createTrackerCompass(int amount) {
        ItemStack item = new ItemStack(Material.COMPASS, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§c§l추적 나침반");
            meta.setLore(List.of(
                    "§7들고 있으면 가장 가까운 플레이어를 가리킵니다."
            ));
            meta.getPersistentDataContainer().set(trackerCompassKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isTrackerCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Byte v = meta.getPersistentDataContainer().get(trackerCompassKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    private Player findNearestPlayer(Player from) {
        Player nearest = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Player p : from.getWorld().getPlayers()) {
            if (p.equals(from)) continue;
            if (!p.isOnline() || p.isDead()) continue;
            if (p.getGameMode() == GameMode.SPECTATOR) continue;

            double d = p.getLocation().distanceSquared(from.getLocation());
            if (d < bestDistSq) {
                bestDistSq = d;
                nearest = p;
            }
        }
        return nearest;
    }

    private void startTrackerTask() {
        if (trackerTask != null) trackerTask.cancel();

        trackerTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR) continue;

                ItemStack main = p.getInventory().getItemInMainHand();
                if (!isTrackerCompass(main)) continue;

                Player nearest = findNearestPlayer(p);
                if (nearest != null) {
                    p.setCompassTarget(nearest.getLocation());
                }
            }
        }, 0L, 10L);
    }

    private void startCooldownActionBar(Player player, PlayerState state, Ability ability) {
        if (state.cooldownTask != null) {
            state.cooldownTask.cancel();
            state.cooldownTask = null;
        }

        state.cooldownTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                long now = System.currentTimeMillis();
                long remainingMs = state.nextUsableAtMs - now;

                if (remainingMs <= 0) {
                    player.sendActionBar("");
                    state.cooldownTask = null;
                    cancel();
                    return;
                }

                long sec = (remainingMs + 999) / 1000;
                player.sendActionBar("§7[§b" + ability.name() + "§7] §f쿨타임 §c" + sec + "s");
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        PlayerState state = getState(player);
        if (state.getAbility() == null) return;

        state.getAbility().onDamage(this, event);
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        PlayerState state = getState(player);
        if (state.getAbility() == null) return;

        state.getAbility().onFish(this, event);
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        PlayerState state = getState(player);
        if (state.getAbility() == null) return;

        state.getAbility().onConsume(this, event);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerState state = getState(player);
        if (state.getAbility() == null) return;

        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        state.getAbility().onMove(this, event);
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;

        PlayerState state = getState(player);
        if (state.getAbility() == null) return;

        state.getAbility().onAttack(this, event);
    }

    private ItemStack createRerollTicket(int amount) {
        ItemStack item = new ItemStack(Material.PAPER, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b§l리롤권");
            meta.setLore(List.of(
                    "§7리롤권을 손에 들고 우클릭하면",
                    "§e능력이 랜덤으로 변경됩니다."
            ));

            meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().set(rerollKey, PersistentDataType.BYTE, (byte) 1);

            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isRerollTicket(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Byte v = meta.getPersistentDataContainer().get(rerollKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("ability")) return false;

        if (args.length == 0) {
            sender.sendMessage("Usage: /ability me | /ability give <player> <id> | /ability start | /ability startmini");
            return true;
        }

        if (args[0].equalsIgnoreCase("me")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Players only.");
                return true;
            }
            PlayerState st = getState(p);
            if (st.getAbility() == null) {
                p.sendMessage("No ability assigned.");
            } else {
                p.sendMessage("Ability: " + st.getAbility().name() + " (" + st.getAbility().id() + ")");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (args.length < 3) {
                sender.sendMessage("Usage: /ability give <player> <abilityId>");
                return true;
            }
            Player target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("플레이어를 찾을수 없습니다.");
                return true;
            }
            Ability a = getAbility(args[2]);
            if (a == null) {
                sender.sendMessage("Ability not found: " + args[2]);
                sender.sendMessage("Registered: " + registry.keySet());
                return true;
            }
            grant(target, a);
            sender.sendMessage("Gave " + a.name() + " to " + target.getName());
            target.sendMessage("You got ability: " + a.name());
            return true;
        }

        if (args[0].equalsIgnoreCase("reroll")) {
            if (!sender.isOp()) {
                sender.sendMessage("OP only.");
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage("Usage: /ability reroll <player>");
                return true;
            }

            if (registry.isEmpty()) {
                sender.sendMessage("No abilities registered.");
                return true;
            }

            Player target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("플레이어를 찾을 수 없습니다.");
                return true;
            }

            List<Ability> abilities = new ArrayList<>(registry.values());
            Random random = new Random();

            Ability newAbility = abilities.get(random.nextInt(abilities.size()));

            PlayerState state = getState(target);
            if (state.cooldownTask != null) {
                state.cooldownTask.cancel();
                state.cooldownTask = null;
            }
            target.sendActionBar("");

            grant(target, newAbility);

            sender.sendMessage("§a" + target.getName() + "의 능력을 §e" + newAbility.name() + "§a(으)로 리롤했습니다.");
            target.sendMessage("§e[리롤] §f능력이 §a" + newAbility.name() + "§f(으)로 변경되었습니다!");

            return true;
        }

        if (args[0].equalsIgnoreCase("rerollall")) {
            if (!sender.isOp()) {
                sender.sendMessage("OP only.");
                return true;
            }
            if (registry.isEmpty()) {
                sender.sendMessage("No abilities registered.");
                return true;
            }

            List<Ability> abilities = new ArrayList<>(registry.values());
            Random random = new Random();

            for (Player p : plugin.getServer().getOnlinePlayers()) {
                Ability newAbility = abilities.get(random.nextInt(abilities.size()));

                PlayerState state = getState(p);
                if (state.cooldownTask != null) {
                    state.cooldownTask.cancel();
                    state.cooldownTask = null;
                }
                p.sendActionBar("");

                grant(p, newAbility);
                p.sendMessage("§e[전체리롤] §f능력이 §a" + newAbility.name() + "§f(으)로 변경!");
            }

            sender.sendMessage("§aRerolled abilities for all online players.");
            return true;
        }

        if (args[0].equalsIgnoreCase("start")) {
            if (!sender.isOp()) {
                sender.sendMessage("OP only.");
                return true;
            }
            if (registry.isEmpty()) {
                sender.sendMessage("No abilities registered.");
                return true;
            }

            List<Ability> abilities = new ArrayList<>(registry.values());
            Random random = new Random();

            for (Player p : plugin.getServer().getOnlinePlayers()) {
                Ability randomAbility = abilities.get(random.nextInt(abilities.size()));
                grant(p, randomAbility);
                p.sendMessage("§a[능력] " + randomAbility.name() + " 능력이 부여되었습니다!");

                new BukkitRunnable() {
                    int t = 0;
                    @Override
                    public void run() {
                        if (!p.isOnline() || t > 60) {
                            cancel();
                            return;
                        }
                        p.sendActionBar("§6당신의 능력: §e" + randomAbility.name());
                        t += 10;
                    }
                }.runTaskTimer(plugin, 0L, 10L);

                giveDefaultStartItems(p, true);
            }

            gameManager.startGame();
            sender.sendMessage("Ability game started! Random abilities assigned.");
            return true;
        }

        if (args[0].equalsIgnoreCase("startmini")) {
            if (!sender.isOp()) {
                sender.sendMessage("OP only.");
                return true;
            }
            if (registry.isEmpty()) {
                sender.sendMessage("No abilities registered.");
                return true;
            }

            List<Ability> abilities = new ArrayList<>(registry.values());
            Random random = new Random();

            for (Player p : plugin.getServer().getOnlinePlayers()) {
                Ability randomAbility = abilities.get(random.nextInt(abilities.size()));
                grant(p, randomAbility);
                p.sendMessage("§d[미니 도능] §f" + randomAbility.name() + " 능력이 부여되었습니다!");

                new BukkitRunnable() {
                    int t = 0;
                    @Override
                    public void run() {
                        if (!p.isOnline() || t > 60) {
                            cancel();
                            return;
                        }
                        p.sendActionBar("§d미니 도능 능력: §e" + randomAbility.name());
                        t += 10;
                    }
                }.runTaskTimer(plugin, 0L, 10L);

                giveMiniStartItems(p, true);
            }

            gameManager.startMiniGame();
            sender.sendMessage("Mini ability game started! Random abilities assigned.");
            return true;
        }

        if (args[0].equalsIgnoreCase("startpick")) {
            if (!sender.isOp()) {
                sender.sendMessage("OP only.");
                return true;
            }
            if (abilityPickManager == null) {
                sender.sendMessage("AbilityPickManager is not connected.");
                return true;
            }

            abilityPickManager.startPickGame(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("rerollitem")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Players only.");
                return true;
            }
            if (!p.isOp()) {
                p.sendMessage("OP only.");
                return true;
            }

            int amount = 1;
            if (args.length >= 2) {
                try {
                    amount = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {}
            }

            p.getInventory().addItem(createRerollTicket(amount));
            p.sendMessage("§a리롤권 " + amount + "개를 지급했습니다.");
            return true;
        }

        return true;
    }

    public void giveDefaultStartItems(Player p, boolean giveRerollTicket) {
        if (giveRerollTicket) {
            p.getInventory().addItem(createRerollTicket(1));
        }

        p.getInventory().addItem(createTrackerCompass(1));

        giveOrDrop(p, new ItemStack(Material.LAPIS_LAZULI, NORMAL_LAPIS_COUNT));
        giveOrDrop(p, new ItemStack(Material.IRON_INGOT, 29));
        giveOrDrop(p, new ItemStack(Material.STICK, 1));
        giveOrDrop(p, new ItemStack(Material.WHITE_WOOL, 64));
        giveOrDrop(p, new ItemStack(Material.NETHER_STAR, 1));
        giveOrDrop(p, new ItemStack(Material.BREAD, 128));

        p.giveExp(NORMAL_XP_AMOUNT);
    }

    public Ability getRegisteredAbility(String key) {
        if (key == null) return null;
        return registry.get(key.toLowerCase());
    }

    public String getAbilityKey(Ability target) {
        if (target == null) return null;

        for (Map.Entry<String, Ability> entry : registry.entrySet()) {
            if (entry.getValue() == target) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void giveMiniStartItems(Player p, boolean giveRerollTicket) {
        if (giveRerollTicket) {
            p.getInventory().addItem(createRerollTicket(1));
        }

        p.getInventory().addItem(createTrackerCompass(1));

        giveOrDrop(p, new ItemStack(Material.LAPIS_LAZULI, MINI_LAPIS_COUNT));
        giveOrDrop(p, new ItemStack(Material.IRON_INGOT, 16));
        giveOrDrop(p, new ItemStack(Material.STICK, 1));
        giveOrDrop(p, new ItemStack(Material.WHITE_WOOL, 32));
        giveOrDrop(p, new ItemStack(Material.NETHER_STAR, 1));
        giveOrDrop(p, new ItemStack(Material.BREAD, 32));

        p.giveExp(MINI_XP_AMOUNT);
    }

    private void giveOrDrop(Player p, ItemStack item) {
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            leftover.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
        }
    }

    public void registerListeners() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startTrackerTask();
    }

    public void shutdown() {
        if (trackerTask != null) {
            trackerTask.cancel();
            trackerTask = null;
        }
        states.clear();
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getItem() == null) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (isRerollTicket(item)) {
            PlayerState state = getState(player);

            if (registry.isEmpty()) {
                player.sendMessage("§c[리롤권] 등록된 능력이 없습니다.");
                return;
            }

            List<Ability> abilities = new ArrayList<>(registry.values());
            Ability newAbility = abilities.get(new Random().nextInt(abilities.size()));

            item.setAmount(item.getAmount() - 1);

            if (state.cooldownTask != null) {
                state.cooldownTask.cancel();
                state.cooldownTask = null;
            }
            player.sendActionBar("");

            grant(player, newAbility);

            player.sendMessage("§b[리롤권] §f능력이 §a" + newAbility.name() + "§f(으)로 변경되었습니다!");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);

            event.setCancelled(true);
            return;
        }

        if (event.getItem().getType() != Material.NETHER_STAR) return;

        PlayerState state = getState(player);
        if (state.ability == null) {
            player.sendMessage("당신은 무능력자입니다.");
            return;
        }

        long now = System.currentTimeMillis();

        if (now < state.nextUsableAtMs) {
            startCooldownActionBar(player, state, state.ability);
            return;
        }

        boolean activated = state.ability.activate(this, player);
        if (activated) {
            state.nextUsableAtMs = now + state.ability.cooldownSeconds() * 1000L;
            startCooldownActionBar(player, state, state.ability);
        }
    }

    public static class PlayerState {
        private final UUID playerId;
        private Ability ability;
        private long nextUsableAtMs;
        private BukkitTask cooldownTask;

        public PlayerState(UUID playerId) {
            this.playerId = playerId;
        }

        public UUID getPlayerId() {
            return playerId;
        }

        public Ability getAbility() {
            return ability;
        }

        public long getNextUsableAtMs() {
            return nextUsableAtMs;
        }
    }
}