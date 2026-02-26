package my.pkg;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import my.pkg.abilities.Ability;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.Map;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

public class AbilitySystem implements Listener, CommandExecutor {
    private final JavaPlugin plugin;
    // 능력 등록소: id -> 능력 구현체
    private final Map<String, Ability> registry = new HashMap<>();
    // 플레이어별 상태(능력/쿨타임) 저장
    private final Map<UUID, PlayerState> states = new HashMap<>();

    private final GameManager gameManager;

    private final NamespacedKey trackerCompassKey;

    private BukkitTask trackerTask;
    private final NamespacedKey rerollKey;

    private static final int LAPIS_COUNT = 16; // n값
    private static final int XP_AMOUNT = 300;   // n값 (레벨 아님, exp 포인트)

    public AbilitySystem(JavaPlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.rerollKey = new NamespacedKey(plugin, "reroll_ticket");
        this.trackerCompassKey = new NamespacedKey(plugin, "tracker_compass");

    }
    public JavaPlugin getPlugin() {
        return plugin;
    }

    public void register(Ability ability) {
        registry.put(ability.id().toLowerCase(), ability);
    }

    public Ability getAbility(String id) {
        if (id == null) {
            return null;
        }
        return registry.get(id.toLowerCase());
    }

    public PlayerState getState(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), PlayerState::new);
    }

    public void grant(Player player, Ability ability) {
        PlayerState state = getState(player);
        if (state.cooldownTask != null) {
            state.cooldownTask.cancel();
            state.cooldownTask = null;
        }
        player.sendActionBar("");
        if (state.ability != null) {
            // 기존 능력 회수 훅 호출
            state.ability.onRemove(this, player);
        }
        state.ability = ability;
        // 쿨타임 초기화
        state.nextUsableAtMs = 0;
        if (ability != null) {
            // 신규 능력 지급 훅 호출
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

            // ✅ 관전자 제외
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
        }, 0L, 10L); // 10틱(0.5초)마다 갱신
    }

    private void startCooldownActionBar(Player player, PlayerState state, Ability ability) {
        // 기존 표시 작업 있으면 제거
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

                // 쿨 끝
                if (remainingMs <= 0) {
                    // 액션바 지우기(빈 문자열)
                    player.sendActionBar("");
                    state.cooldownTask = null;
                    cancel();
                    return;
                }

                long sec = (remainingMs + 999) / 1000;

                // 원하는 문구로 수정 가능
                player.sendActionBar("§7[§b" + ability.name() + "§7] §f쿨타임 §c" + sec + "s");
            }
        }.runTaskTimer(plugin, 0L, 10L); // 10틱=0.5초마다 갱신 (부드럽게)
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        PlayerState state = getState(player);
        if (state.getAbility() == null) return;

        // ✅ 능력에게 넘김 (패시브)
        state.getAbility().onDamage(this, event);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerState state = getState(player);
        if (state.getAbility() == null) return;

        // 블록 단위로 바뀐 경우만 처리 (성능)
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

            // 반짝이
            meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

            // ✅ 진짜 리롤권인지 식별용 PDC
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
            sender.sendMessage("Usage: /ability me | /ability give <player> <id> | /ability start");
            return true;
        }

        // /ability me
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

        // /ability give <player> <id>
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

                // 쿨타임 액션바 표시/태스크 정리까지 포함해서 교체
                PlayerState state = getState(p);
                if (state.cooldownTask != null) {
                    state.cooldownTask.cancel();
                    state.cooldownTask = null;
                }
                p.sendActionBar("");

                grant(p, newAbility);
                p.sendMessage("§e[전체리롤] §f능력이 §a" + newAbility.name() + "§f(으)로 변경!");
            }

            sender.sendMessage("§aRerolled abilities for all online players (no items given).");
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

            // 등록된 능력 목록을 리스트로 변환
            List<Ability> abilities = new ArrayList<>(registry.values());
            Random random = new Random();

            // 온라인 플레이어 전원에게 랜덤 능력 배정
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                Ability randomAbility = abilities.get(random.nextInt(abilities.size()));
                grant(p, randomAbility);
                p.sendMessage("§a[능력] " + randomAbility.name() + " 능력이 부여되었습니다!");

                p.getInventory().addItem(createRerollTicket(1));
                p.sendMessage("§b[리롤권] 1개가 지급되었습니다!");

                p.getInventory().addItem(createTrackerCompass(1));
                p.sendMessage("§c[추적 나침반] 1개가 지급되었습니다!");

                giveOrDrop(p, new ItemStack(Material.LAPIS_LAZULI, LAPIS_COUNT)); // 청금석 n개
                giveOrDrop(p, new ItemStack(Material.IRON_INGOT, 29));            // 철 29개
                giveOrDrop(p, new ItemStack(Material.STICK, 1));                  // 막대기 1개
                giveOrDrop(p, new ItemStack(Material.WHITE_WOOL, 64));            // 양털 한셋(흰양털 64)
                giveOrDrop(p, new ItemStack(Material.NETHER_STAR, 1));            // 네더의 별

                p.giveExp(XP_AMOUNT); // XP n (포인트 단위)

                gameManager.startGame();
            }

            sender.sendMessage("Ability game started! Random abilities assigned.");
            return true;
        }

        //리롤권 부여 ability rerollitem 1
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

    private void giveOrDrop(Player p, ItemStack item) {
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            leftover.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
        }
    }

    public void registerListeners() {
        // 발동 입력 리스너 등록 (AbilitySystem 자체가 Listener)
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

        // ✅ 메인핸드 이벤트만 처리 (오프핸드 중복 발동 방지)
        if (event.getHand() != EquipmentSlot.HAND) return;

        // 발동 트리거: 네더스타 우클릭
        if (event.getItem() == null) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // ✅ 리롤권 처리(양손 + 우클릭)
        if (isRerollTicket(item)) {
            // "양손 들기" 판정: 오프핸드가 비어있지 않으면(=양손에 뭔가 든 상태)
            ItemStack off = player.getInventory().getItemInOffHand();
            boolean holdingTwoHands = off != null && off.getType() != Material.AIR;

            // 능력 랜덤 변경
            PlayerState state = getState(player);

            if (registry.isEmpty()) {
                player.sendMessage("§c[리롤권] 등록된 능력이 없습니다.");
                return;
            }

            // 기존 능력 제외하고 뽑고 싶으면 여기서 필터 가능
            List<Ability> abilities = new ArrayList<>(registry.values());
            Ability newAbility = abilities.get(new Random().nextInt(abilities.size()));

            // ✅ 1장 소모
            item.setAmount(item.getAmount() - 1);

            // ✅ 쿨타임 표시 제거
            if (state.cooldownTask != null) {
                state.cooldownTask.cancel();
                state.cooldownTask = null;
            }
            player.sendActionBar("");

            // ✅ 능력 교체
            grant(player, newAbility);

            player.sendMessage("§b[리롤권] §f능력이 §a" + newAbility.name() + "§f(으)로 변경되었습니다!");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);

            // 네더스타 발동이랑 겹치지 않게 이벤트 종료
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
            // 이미 쿨표시가 돌고 있을텐데, 혹시 없으면 다시 시작
            startCooldownActionBar(player, state, state.ability);
            return;
        }

        boolean activated = state.ability.activate(this, player);
        if (activated) {
            state.nextUsableAtMs = now + state.ability.cooldownSeconds() * 1000L;

            // ✅ 능력 사용 직후부터 액션바로 쿨타임 표시
            startCooldownActionBar(player, state, state.ability);
        }
    }

    public static class PlayerState {
        private final UUID playerId;
        // 현재 보유 능력
        private Ability ability;
        // 다음 사용 가능 시각(ms)
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
// /ability give 닉네임 능력
// /ability start
// /ability me