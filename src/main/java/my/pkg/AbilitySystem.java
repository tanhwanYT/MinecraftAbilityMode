package my.pkg;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import my.pkg.abilities.Ability;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

public class AbilitySystem implements Listener, CommandExecutor {
    private final JavaPlugin plugin;
    // 능력 등록소: id -> 능력 구현체
    private final Map<String, Ability> registry = new HashMap<>();
    // 플레이어별 상태(능력/쿨타임) 저장
    private final Map<UUID, PlayerState> states = new HashMap<>();

    public AbilitySystem(JavaPlugin plugin) {
        this.plugin = plugin;
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

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        PlayerState state = getState(player);
        if (state.getAbility() == null) return;

        // ✅ 능력에게 넘김 (패시브)
        state.getAbility().onDamage(this, event);
    }

    @EventHandler
    public void onMove(org.bukkit.event.player.PlayerMoveEvent event) {
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
    public void onAttack(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;

        PlayerState state = getState(player);
        if (state.getAbility() == null) return;

        state.getAbility().onAttack(this, event);
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
            }

            sender.sendMessage("Ability game started! Random abilities assigned.");
            return true;
        }
        return true;
    }

    public void registerListeners() {
        // 발동 입력 리스너 등록 (AbilitySystem 자체가 Listener)
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        // 상태만 정리 (저장/로드는 필요 시 추가)
        states.clear();
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // 발동 트리거: 네더스타 우클릭
        if (event.getHand() == null || event.getItem() == null) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getItem().getType() != Material.NETHER_STAR) {
            return;
        }

        Player player = event.getPlayer();
        PlayerState state = getState(player);
        if (state.ability == null) {
            player.sendMessage("당신은 무능력자입니다.");
            return;
        }
        long now = System.currentTimeMillis();
        if (now < state.nextUsableAtMs) {
            // 쿨타임 남았으면 안내만 하고 종료
            long remaining = state.nextUsableAtMs - now;
            long seconds = (remaining + 999) / 1000;
            player.sendMessage("남은 쿨타임 : " + seconds + "s");
            return;
        }

        // 실제 능력 발동
        boolean activated = state.ability.activate(this, player);
        if (activated) {
            // 발동 성공 시 쿨타임 시작
            state.nextUsableAtMs = now + state.ability.cooldownSeconds() * 1000L;
        }
    }

    public static class PlayerState {
        private final UUID playerId;
        // 현재 보유 능력
        private Ability ability;
        // 다음 사용 가능 시각(ms)
        private long nextUsableAtMs;

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