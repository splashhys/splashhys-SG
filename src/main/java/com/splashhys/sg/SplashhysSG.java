package com.splashhys.sg;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.*;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class SplashhysSG extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private enum State { WAITING, ACTIVE, ELIMINATED, SPECTATOR }
    private enum Role { PLAYER, GUARD, FRONTMAN }
    private enum Game { NONE, RLGL, DALGONA, NIGHTFIGHT, TUGOFWAR, MARBLES, GLASSBRIDGE, SQUIDGAME }

    private final Map<UUID, P> players = new HashMap<>();
    private final Set<UUID> queue = new LinkedHashSet<>();
    private final Set<UUID> frontmen = new HashSet<>();
    private final Set<UUID> guards = new HashSet<>();
    private final Map<String, Location> points = new HashMap<>();
    private final Map<String, Region> regions = new HashMap<>();
    private final Map<UUID, UUID> marbleRequests = new HashMap<>();
    private final Map<UUID, UUID> marblePairs = new HashMap<>();
    private final Map<UUID, Integer> marbleCount = new HashMap<>();
    private final Map<UUID, Integer> glassOrder = new HashMap<>();
    private final Map<UUID, Integer> glassCurrent = new HashMap<>();
    private final Map<Integer, Boolean> glassSafeLeft = new HashMap<>();
    private final Map<UUID, String> tugTeam = new HashMap<>();
    private final Map<UUID, Long> lastTugClick = new HashMap<>();
    private final Map<UUID, Location> rlglLast = new HashMap<>();
    private final Map<UUID, Long> rlglGraceUntil = new HashMap<>();
    private final Map<String, DalgonaTeam> dalgonaTeams = new LinkedHashMap<>();

    private Game game = Game.NONE;
    private boolean eventStarted = false;
    private boolean rlglRed = false;
    private long rlglTransitionUntil = 0;
    private int tugScore = 0;
    private BukkitTask rlglTask;
    private BukkitTask nightTask;
    private BukkitTask tugTask;
    private BukkitTask glassTask;
    private BukkitTask squidTask;
    private BukkitTask dalgonaTask;
    private UUID currentGlassPlayer;
    private final Map<UUID, SetupSession> setupSessions = new HashMap<>();
    private final NamespacedKey setupKey;
    private final NamespacedKey queueSignKey;

    private Scoreboard board;
    private Team redTeam, blueTeam, frontmanTeam, guardTeam;

    @Override public void onEnable() {
        setupKey = new NamespacedKey(this, "setup_tool");
        queueSignKey = new NamespacedKey(this, "queue_sign");
        saveDefaultConfig();
        loadData();
        loadSetup();
        setupScoreboard();
        Objects.requireNonNull(getCommand("sg")).setExecutor(this);
        Objects.requireNonNull(getCommand("sg")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Splashhys SG 2.0.0 enabled.");
    }

    @Override public void onDisable() {
        cancelTasks();
        saveData();
        clearAllEffects();
    }

    private void setupScoreboard() {
        board = Bukkit.getScoreboardManager().getMainScoreboard();
        redTeam = getOrCreateTeam("sg_red", ChatColor.RED);
        blueTeam = getOrCreateTeam("sg_blue", ChatColor.BLUE);
        frontmanTeam = getOrCreateTeam("sg_frontman", ChatColor.GOLD);
        guardTeam = getOrCreateTeam("sg_guard", ChatColor.DARK_GRAY);
    }

    private Team getOrCreateTeam(String name, ChatColor color) {
        Team t = board.getTeam(name);
        if (t == null) t = board.registerNewTeam(name);
        t.setColor(color);
        return t;
    }

    private void loadData() {
        File f = new File(getDataFolder(), "players.yml");
        if (!f.exists()) return;
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        for (String s : y.getConfigurationSection("players") == null ? List.<String>of() :
                y.getConfigurationSection("players").getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(s); } catch (Exception e) { continue; }
            P p = new P(id, y.getInt("players."+s+".number"), State.valueOf(y.getString("players."+s+".state","WAITING")));
            players.put(id, p);
        }
        for (String s : y.getStringList("frontmen")) try { frontmen.add(UUID.fromString(s)); } catch(Exception ignored){}
        for (String s : y.getStringList("guards")) try { guards.add(UUID.fromString(s)); } catch(Exception ignored){}
        eventStarted = y.getBoolean("eventStarted", false);
        game = Game.valueOf(y.getString("game","NONE"));
    }

    private void saveData() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        for (P p : players.values()) {
            y.set("players."+p.id+".number", p.number);
            y.set("players."+p.id+".state", p.state.name());
        }
        y.set("frontmen", frontmen.stream().map(UUID::toString).toList());
        y.set("guards", guards.stream().map(UUID::toString).toList());
        y.set("eventStarted", eventStarted);
        y.set("game", game.name());
        try { y.save(new File(getDataFolder(), "players.yml")); } catch(Exception e) { getLogger().warning(e.getMessage()); }
    }

    private void cancelTasks() {
        if (rlglTask != null) rlglTask.cancel();
        if (nightTask != null) nightTask.cancel();
        if (tugTask != null) tugTask.cancel();
        if (glassTask != null) glassTask.cancel();
        if (squidTask != null) squidTask.cancel();
        if (dalgonaTask != null) dalgonaTask.cancel();
        rlglTask=nightTask=tugTask=glassTask=squidTask=dalgonaTask=null;
    }

    private void clearAllEffects() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            p.removePotionEffect(PotionEffectType.DARKNESS);
            p.setAllowFlight(false);
            p.setFlying(false);
        }
    }

    private boolean isFrontman(CommandSender s) {
        return s instanceof Player p && frontmen.contains(p.getUniqueId()) || s.hasPermission("squid.frontman");
    }

    private boolean isGuard(Player p) { return guards.contains(p.getUniqueId()) || p.hasPermission("squid.guard"); }

    private P contestant(UUID id) { return players.get(id); }

    private boolean active(Player p) {
        P x = contestant(p.getUniqueId());
        return x != null && x.state == State.ACTIVE;
    }

    private int activeCount() {
        int n=0; for(P p:players.values()) if(p.state==State.ACTIVE) n++; return n;
    }

    private int eliminatedCount() {
        int n=0; for(P p:players.values()) if(p.state==State.ELIMINATED) n++; return n;
    }

    private String playerLabel(UUID id) {
        P p=players.get(id);
        Player online=Bukkit.getPlayer(id);
        return "[" + (p==null ? "?" : String.format("%03d",p.number)) + "] " + (online==null ? id.toString().substring(0,8) : online.getName());
    }

    private void refreshName(Player p) {
        P data = players.get(p.getUniqueId());
        String name = p.getName();
        if (frontmen.contains(p.getUniqueId())) {
            frontmanTeam.addEntry(name); p.setPlayerListName(ChatColor.GOLD+"[FRONTMAN] "+name); return;
        }
        if (guards.contains(p.getUniqueId())) {
            guardTeam.addEntry(name); p.setPlayerListName(ChatColor.DARK_GRAY+"[GUARD] "+name); return;
        }
        String prefix = data == null ? "" : "["+String.format("%03d",data.number)+"] ";
        if ("RED".equals(tugTeam.get(p.getUniqueId()))) redTeam.addEntry(name);
        if ("BLUE".equals(tugTeam.get(p.getUniqueId()))) blueTeam.addEntry(name);
        p.setPlayerListName(prefix+name);
        p.setDisplayName(prefix+name);
    }

    private void send(Player p, String s) { p.sendMessage(ChatColor.AQUA+"[SG] "+ChatColor.RESET+s); }

    private Location point(String key) { return points.get(key); }
    private Region region(String key) { return regions.get(key); }

    private boolean ready(Game g) {
        List<String> missing = new ArrayList<>();
        switch(g) {
            case RLGL -> { reqPoint(missing,"rlgl.start"); reqPoint(missing,"rlgl.finish"); reqPoint(missing,"rlgl.doll"); }
            case DALGONA -> {
                for(int i=1;i<=4;i++){ reqPoint(missing,"dalgona."+i+".spawn"); reqRegion(missing,"dalgona."+i+".reference"); reqRegion(missing,"dalgona."+i+".build"); }
            }
            case NIGHTFIGHT -> { reqPoint(missing,"nightfight.spawn"); reqRegion(missing,"nightfight.area"); }
            case TUGOFWAR -> { reqPoint(missing,"tugofwar.red"); reqPoint(missing,"tugofwar.blue"); reqPoint(missing,"tugofwar.red_edge"); reqPoint(missing,"tugofwar.blue_edge"); }
            case MARBLES -> { reqPoint(missing,"marbles.spectator"); reqPoint(missing,"marbles.arena"); }
            case GLASSBRIDGE -> {
                reqPoint(missing,"glassbridge.entrance"); reqPoint(missing,"glassbridge.exit");
                for(int i=1;i<=12;i++){ reqPoint(missing,"glassbridge."+i+".left"); reqPoint(missing,"glassbridge."+i+".right"); }
            }
            case SQUIDGAME -> { reqPoint(missing,"squidgame.spawn"); reqRegion(missing,"squidgame.area"); }
            default -> {}
        }
        return missing.isEmpty();
    }

    private void reqPoint(List<String> m,String k){ if(!points.containsKey(k))m.add(k); }
    private void reqRegion(List<String> m,String k){ if(!regions.containsKey(k))m.add(k); }

    private void startEvent() {
        if(eventStarted) return;
        int min=getConfig().getInt("queue.minimum-players",48);
        if(queue.size()<min) { broadcast("Need at least "+min+" queued players."); return; }
        eventStarted=true; game=Game.NONE;
        for(UUID id:queue) {
            P p=players.get(id); if(p!=null){p.state=State.ACTIVE; assignNightVision(Bukkit.getPlayer(id));}
        }
        sendAll("SQUID GAME EVENT STARTED");
        saveData();
        updateSigns();
    }

    private void startGame(Game g) {
        if(!eventStarted) { broadcast("Start the event first with /sg start"); return; }
        if(!ready(g)) { broadcast("Setup is incomplete. Use /sg setup status."); return; }
        cancelTasks(); game=g; rlglRed=false; tugScore=0;
        if(g==Game.TUGOFWAR && activeCount()<getConfig().getInt("games.tugofwar.minimum-players",48)) {
            broadcast("Tug of War requires at least 48 players."); return;
        }
        switch(g) {
            case RLGL -> startRLGL();
            case DALGONA -> startDalgona();
            case NIGHTFIGHT -> startNightFight();
            case TUGOFWAR -> startTug();
            case MARBLES -> startMarbles();
            case GLASSBRIDGE -> startGlass();
            case SQUIDGAME -> startSquid();
            default -> {}
        }
        saveData();
    }

    private void startRLGL() {
        broadcast("RED LIGHT, GREEN LIGHT");
        teleportActive(point("rlgl.start"));
        for(Player p: Bukkit.getOnlinePlayers()) if(active(p)) assignNightVision(p);
        scheduleRLGL();
    }

    private void scheduleRLGL() {
        long delay = ThreadLocalRandom.current().nextLong(
                getConfig().getInt("games.rlgl.green-min-seconds",3)*20L,
                getConfig().getInt("games.rlgl.green-max-seconds",7)*20L+1);
        rlglTask=Bukkit.getScheduler().runTaskLater(this,()->{
            rlglRed=true; rlglTransitionUntil=System.currentTimeMillis()+250;
            broadcastTitle("RED LIGHT","",ChatColor.RED);
            checkRLGLMovement();
            long redDelay=ThreadLocalRandom.current().nextLong(
                    getConfig().getInt("games.rlgl.red-min-seconds",2)*20L,
                    getConfig().getInt("games.rlgl.red-max-seconds",5)*20L+1);
            rlglTask=Bukkit.getScheduler().runTaskLater(this,()->{
                rlglRed=false; broadcastTitle("GREEN LIGHT","",ChatColor.GREEN); scheduleRLGL();
            },redDelay);
        },delay);
    }

    private void checkRLGLMovement() {
        if(!rlglRed) return;
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            int ticks=0;
            public void run() {
                if(!rlglRed || ++ticks>100) return;
                Location finish=point("rlgl.finish");
                for(Player p:Bukkit.getOnlinePlayers()) {
                    if(!active(p)) continue;
                    Location old=rlglLast.get(p.getUniqueId());
                    Location now=p.getLocation();
                    if(old!=null && System.currentTimeMillis()>rlglTransitionUntil) {
                        double dx=now.getX()-old.getX(), dz=now.getZ()-old.getZ();
                        if(Math.hypot(dx,dz)>getConfig().getDouble("games.rlgl.movement-tolerance",0.10)) eliminate(p,"moved on Red Light");
                    }
                    rlglLast.put(p.getUniqueId(),now.clone());
                    if(finish!=null && sameWorld(now,finish) && now.distanceSquared(finish)<9) {
                        p.setMetadata("sg_safe",new org.bukkit.metadata.FixedMetadataValue(this,true));
                    }
                }
            }
        },2,2);
    }

    private void startDalgona() {
        broadcast("DALGONA — COPY THE REFERENCE");
        dalgonaTeams.clear();
        List<UUID> ids=activeIds(); Collections.shuffle(ids);
        for(int i=1;i<=4;i++) dalgonaTeams.put("TEAM"+i,new DalgonaTeam("TEAM"+i, new ArrayList<>()));
        for(int i=0;i<ids.size();i++) dalgonaTeams.get("TEAM"+(i%4+1)).players.add(ids.get(i));
        for(int i=1;i<=4;i++) {
            DalgonaTeam t=dalgonaTeams.get("TEAM"+i);
            Location sp=point("dalgona."+i+".spawn");
            for(UUID id:t.players){ Player p=Bukkit.getPlayer(id); if(p!=null) { p.teleport(sp); assignNightVision(p); } }
            t.reference=region("dalgona."+i+".reference").snapshot();
        }
        dalgonaTask=Bukkit.getScheduler().runTaskTimer(this,()->checkDalgona(),10,10);
    }

    private void checkDalgona() {
        int done=0;
        for(DalgonaTeam t:dalgonaTeams.values()) {
            if(t.finished) {done++; continue;}
            Region target=region("dalgona."+t.name.substring(4)+".build");
            if(target!=null && t.reference.matches(target)) { t.finished=true; done++; broadcast(t.name+" finished!"); }
        }
        if(done>=3) {
            DalgonaTeam last=dalgonaTeams.values().stream().filter(t->!t.finished).findFirst().orElse(null);
            if(last!=null && done==4) eliminateTeam(last);
            if(done==4) stopGame();
        }
    }

    private void eliminateTeam(DalgonaTeam t) {
        for(UUID id:t.players){Player p=Bukkit.getPlayer(id); if(p!=null) eliminate(p,"last Dalgona team");}
        t.finished=true;
    }

    private void startNightFight() {
        broadcast("NIGHT FIGHT PREPARATION");
        teleportActive(point("nightfight.spawn"));
        for(Player p:Bukkit.getOnlinePlayers()) if(active(p)){ p.setGameMode(GameMode.ADVENTURE); assignNightVision(p); }
        int seconds=getConfig().getInt("games.nightfight.preparation-seconds",60);
        nightTask=Bukkit.getScheduler().runTaskTimer(this,new Runnable(){
            int left=seconds;
            public void run(){
                if(left<=0){ nightTask.cancel(); nightTask=null; lightsOut(); return; }
                if(left<=10 || left%10==0) broadcastTitle("LIGHTS OUT IN",""+left+" SECONDS",ChatColor.YELLOW);
                left--;
            }
        },0,20);
    }

    private void lightsOut() {
        broadcastTitle("LIGHTS OUT","",ChatColor.RED);
        for(Player p:Bukkit.getOnlinePlayers()) if(active(p)){
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,20*60*30,0,false,false,false));
            p.setGameMode(GameMode.SURVIVAL);
        }
        // PvP is enabled by the listener while this game is active.
    }

    private void startTug() {
        List<UUID> ids=activeIds(); Collections.shuffle(ids);
        tugTeam.clear();
        for(int i=0;i<ids.size();i++) tugTeam.put(ids.get(i), i<=(ids.size()-1)/2 ? "RED":"BLUE");
        for(UUID id:ids){
            Player p=Bukkit.getPlayer(id); if(p==null)continue;
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            String team=tugTeam.get(id);
            p.teleport(point("tugofwar."+team.toLowerCase()));
            refreshName(p);
        }
        broadcast("TUG OF WAR — CLICK TO PULL");
        tugTask=Bukkit.getScheduler().runTaskTimer(this,()->moveTugTeams(),2,2);
    }

    private void tugClick(Player p) {
        if(game!=Game.TUGOFWAR || !active(p))return;
        long now=System.currentTimeMillis();
        long cd=getConfig().getLong("games.tugofwar.click-cooldown-ms",110);
        if(now-lastTugClick.getOrDefault(p.getUniqueId(),0L)<cd)return;
        lastTugClick.put(p.getUniqueId(),now);
        if("RED".equals(tugTeam.get(p.getUniqueId()))) tugScore--; else tugScore++;
        int win=getConfig().getInt("games.tugofwar.win-score",100);
        if(Math.abs(tugScore)>=win){ String winner=tugScore<0?"RED":"BLUE"; String loser=winner.equals("RED")?"BLUE":"RED"; for(UUID id:new ArrayList<>(tugTeam.keySet())) if(loser.equals(tugTeam.get(id))){Player x=Bukkit.getPlayer(id); if(x!=null)eliminate(x,"lost Tug of War");} broadcast(winner+" TEAM WINS TUG OF WAR"); stopGame(); }
    }

    private void moveTugTeams() {
        Location r=point("tugofwar.red"), b=point("tugofwar.blue");
        if(r==null||b==null)return;
        Vector dir=b.toVector().subtract(r.toVector()).normalize();
        double ratio=Math.max(-0.42,Math.min(0.42,tugScore/(double)getConfig().getInt("games.tugofwar.win-score",100)*0.42));
        for(UUID id:tugTeam.keySet()){
            Player p=Bukkit.getPlayer(id); if(p==null||!active(p))continue;
            String t=tugTeam.get(id);
            Location base="RED".equals(t)?r:b;
            double sign="RED".equals(t)?ratio:ratio;
            Location dest=base.clone().add(dir.clone().multiply(sign*8));
            dest.setYaw(p.getLocation().getYaw()); dest.setPitch(p.getLocation().getPitch());
            p.teleport(dest);
        }
    }

    private void startMarbles() {
        broadcast("MARBLES — CHOOSE YOUR PARTNER");
        // Pair requests are accepted in the lobby before starting.
        List<UUID> ids=activeIds();
        Set<UUID> paired=new HashSet<>(marblePairs.keySet());
        for(UUID id:ids) if(!paired.contains(id)) {
            marblePairs.remove(id);
            marbleCount.put(id,10);
        }
        int unpaired=0;
        for(UUID id:ids) if(!marblePairs.containsKey(id)) unpaired++;
        if(unpaired>1 && unpaired%2==1){
            UUID bye=ids.stream().filter(id->!marblePairs.containsKey(id)).findFirst().orElse(null);
            if(bye!=null) giveMarblesBye(bye);
        }
        for(UUID id:activeIds()) if(marblePairs.containsKey(id)){
            UUID partner=marblePairs.get(id);
            if(id.compareTo(partner)<0) send(Bukkit.getPlayer(id),"Your Marbles opponent is "+playerLabel(partner));
        }
        broadcast("Marbles started. Each pair plays odd/even guesses by clicking the offered choice.");
    }

    private void giveMarblesBye(UUID id){
        Player p=Bukkit.getPlayer(id); if(p==null)return;
        p.setGameMode(GameMode.SPECTATOR);
        P d=players.get(id); if(d!=null)d.state=State.SPECTATOR;
        Location sp=point("marbles.spectator"); if(sp!=null)p.teleport(sp);
        send(p,"You have a BYE and automatically survive Marbles.");
    }

    private void startGlass() {
        broadcast("GLASS BRIDGE");
        List<UUID> ids=activeIds(); Collections.shuffle(ids);
        glassOrder.clear(); glassCurrent.clear(); glassSafeLeft.clear();
        for(int i=0;i<ids.size();i++){glassOrder.put(ids.get(i),i+1);glassCurrent.put(ids.get(i),0);}
        for(int i=1;i<=12;i++) glassSafeLeft.put(i,ThreadLocalRandom.current().nextBoolean());
        teleportActive(point("glassbridge.entrance"));
        for(Player p:Bukkit.getOnlinePlayers()) if(active(p)){p.removePotionEffect(PotionEffectType.NIGHT_VISION);send(p,"Your order: "+glassOrder.get(p.getUniqueId()));}
        currentGlassPlayer=ids.isEmpty()?null:ids.get(0);
        broadcastGlassOrder();
        glassTask=Bukkit.getScheduler().runTaskTimer(this,()->checkGlass(),2,2);
    }

    private void broadcastGlassOrder() {
        List<Map.Entry<UUID,Integer>> list=new ArrayList<>(glassOrder.entrySet());
        list.sort(Map.Entry.comparingByValue());
        for(Player viewer:Bukkit.getOnlinePlayers()){
            if(!active(viewer))continue;
            viewer.sendMessage(ChatColor.GOLD+"===== GLASS BRIDGE ORDER =====");
            for(var e:list) viewer.sendMessage(ChatColor.GRAY+"["+e.getValue()+"] "+playerLabel(e.getKey()));
        }
    }

    private void checkGlass() {
        if(currentGlassPlayer==null)return;
        Player p=Bukkit.getPlayer(currentGlassPlayer);
        if(p==null||!active(p)){advanceGlass();return;}
        int row=glassCurrent.getOrDefault(p.getUniqueId(),0)+1;
        if(row>12){finishGlassPlayer(p);advanceGlass();return;}
        Location left=point("glassbridge."+row+".left"), right=point("glassbridge."+row+".right");
        if(left==null||right==null)return;
        if(near(p.getLocation(),left,1.8)){resolveGlass(p,row,true);return;}
        if(near(p.getLocation(),right,1.8)){resolveGlass(p,row,false);}
    }

    private void resolveGlass(Player p,int row,boolean left){
        boolean safe=Boolean.TRUE.equals(glassSafeLeft.get(row))==left;
        if(safe){
            glassCurrent.put(p.getUniqueId(),row);
            broadcast(playerLabel(p.getUniqueId())+" cleared glass row "+row);
            if(row>=12){finishGlassPlayer(p);advanceGlass();}
        } else {
            Location bad=left?point("glassbridge."+row+".left"):point("glassbridge."+row+".right");
            if(bad!=null) bad.getBlock().setType(Material.AIR,false);
            eliminate(p,"broke the Glass Bridge");
            advanceGlass();
        }
    }

    private void finishGlassPlayer(Player p){
        Location exit=point("glassbridge.exit"); if(exit!=null)p.teleport(exit);
        broadcast(playerLabel(p.getUniqueId())+" crossed the Glass Bridge.");
    }

    private void advanceGlass(){
        List<Map.Entry<UUID,Integer>> list=new ArrayList<>(glassOrder.entrySet());
        list.sort(Map.Entry.comparingByValue());
        int idx=-1; for(int i=0;i<list.size();i++)if(list.get(i).getKey().equals(currentGlassPlayer))idx=i;
        for(int i=idx+1;i<list.size();i++){Player x=Bukkit.getPlayer(list.get(i).getKey());if(x!=null&&active(x)){currentGlassPlayer=x.getUniqueId();return;}}
        stopGame();
    }

    private void startSquid() {
        broadcast("SQUID GAME — FINAL KNOCKOUT");
        teleportActive(point("squidgame.spawn"));
        for(Player p:Bukkit.getOnlinePlayers())if(active(p)){assignNightVision(p);p.setGameMode(GameMode.SURVIVAL);}
        squidTask=Bukkit.getScheduler().runTaskTimer(this,()->checkSquidBounds(),10,10);
    }

    private void checkSquidBounds(){
        Region r=region("squidgame.area"); if(r==null)return;
        for(Player p:Bukkit.getOnlinePlayers())if(active(p)&&!r.contains(p.getLocation()))eliminate(p,"left the Squid Game arena");
        if(activeCount()<=1){
            Player winner=Bukkit.getOnlinePlayers().stream().filter(this::active).findFirst().orElse(null);
            if(winner!=null){broadcastTitle("SQUID GAME WINNER",playerLabel(winner.getUniqueId()),ChatColor.GOLD);players.get(winner.getUniqueId()).state=State.WAITING;}
            stopGame();
        }
    }

    private void stopGame(){
        cancelTasks();
        if(game==Game.NIGHTFIGHT) for(Player p:Bukkit.getOnlinePlayers()){p.removePotionEffect(PotionEffectType.DARKNESS);p.setGameMode(GameMode.SURVIVAL);}
        if(game==Game.TUGOFWAR){tugTeam.clear();for(Player p:Bukkit.getOnlinePlayers())refreshName(p);}
        game=Game.NONE;
        for(Player p:Bukkit.getOnlinePlayers()) if(active(p)) {p.removePotionEffect(PotionEffectType.DARKNESS); assignNightVision(p);}
        broadcast("Game stopped. Frontman may start the next game manually.");
        saveData();
    }

    private void eliminate(Player p,String reason){
        P d=players.get(p.getUniqueId()); if(d==null||d.state!=State.ACTIVE)return;
        d.state=State.ELIMINATED;
        p.setGameMode(GameMode.SPECTATOR);
        p.removePotionEffect(PotionEffectType.NIGHT_VISION);
        p.removePotionEffect(PotionEffectType.DARKNESS);
        broadcast(ChatColor.RED+playerLabel(p.getUniqueId())+" ELIMINATED "+ChatColor.GRAY+"("+reason+")");
    }

    private void spectator(Player p){
        P d=players.get(p.getUniqueId()); if(d!=null)d.state=State.SPECTATOR;
        p.setGameMode(GameMode.SPECTATOR);
    }

    private void teleportActive(Location l){if(l==null)return;for(Player p:Bukkit.getOnlinePlayers())if(active(p))p.teleport(l);}
    private boolean sameWorld(Location a,Location b){return a.getWorld()!=null&&b.getWorld()!=null&&a.getWorld().equals(b.getWorld());}
    private boolean near(Location a,Location b,double d){return sameWorld(a,b)&&a.distanceSquared(b)<=d*d;}
    private List<UUID> activeIds(){List<UUID>x=new ArrayList<>();for(P p:players.values())if(p.state==State.ACTIVE)x.add(p.id);return x;}

    private void assignNightVision(Player p){if(p==null||isStaff(p))return;if(game==Game.TUGOFWAR||game==Game.GLASSBRIDGE)return;p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,20*60*60,0,false,false,false));}
    private boolean isStaff(Player p){return frontmen.contains(p.getUniqueId())||guards.contains(p.getUniqueId())||p.hasPermission("squid.frontman")||p.hasPermission("squid.guard");}

    private void broadcast(String s){for(Player p:Bukkit.getOnlinePlayers())p.sendMessage(ChatColor.AQUA+"[SQUID GAME] "+ChatColor.RESET+s);}
    private void broadcastTitle(String title,String sub,ChatColor c){for(Player p:Bukkit.getOnlinePlayers())p.sendTitle(c+title,ChatColor.WHITE+sub,5,20,5);}

    private void updateSigns(){
        for(World w:Bukkit.getWorlds()) for(int x=-1000;x<=1000;x++) { /* intentionally no scan; signs are updated when clicked */ }
    }

    private ItemStack setupTool(String g){
        ItemStack item=new ItemStack(Material.COMPASS);
        ItemMeta m=item.getItemMeta(); m.setDisplayName(ChatColor.GOLD+"SG Setup Tool");
        m.setLore(List.of(ChatColor.GRAY+"Left-click / right-click blocks",ChatColor.GRAY+"to place the next setup point."));
        m.getPersistentDataContainer().set(setupKey,PersistentDataType.STRING,g);
        item.setItemMeta(m); return item;
    }

    private void beginSetup(Player p,String g){
        setupSessions.put(p.getUniqueId(),new SetupSession(g));
        p.getInventory().addItem(setupTool(g));
        send(p,"Setup mode for "+g+". "+setupHelp(g));
    }

    private String setupHelp(String g){
        return switch(g.toLowerCase()){
            case "rlgl"->"Click start, finish, then doll.";
            case "dalgona"->"For each arena: spawn, reference corner 1, reference corner 2, build corner 1, build corner 2.";
            case "nightfight"->"Click spawn, then area corner 1 and corner 2.";
            case "tugofwar"->"Click red spawn, blue spawn, red edge, blue edge.";
            case "marbles"->"Click spectator, then arena.";
            case "glassbridge"->"Click entrance, exit, then LEFT and RIGHT for rows 1-12.";
            case "squidgame"->"Click spawn, then arena corner 1 and corner 2.";
            default->"Unknown game.";
        };
    }

    private void setupClick(Player p, Block b, boolean right){
        SetupSession s=setupSessions.get(p.getUniqueId()); if(s==null)return;
        String k=s.nextKey();
        if(k==null){send(p,"Setup complete. Use /sg setup status.");setupSessions.remove(p.getUniqueId());return;}
        if(k.endsWith(".corner1")){s.temp1=b.getLocation();send(p,"Corner 1 saved. Now click the opposite corner.");return;}
        if(k.endsWith(".corner2")){
            if(s.temp1==null){send(p,"Click corner 1 first.");return;}
            String regionKey=k.substring(0,k.length()-8);
            regions.put(regionKey,new Region(s.temp1,b.getLocation()));
            s.advance(); s.temp1=null; send(p,"Saved "+regionKey); saveSetup(); return;
        }
        points.put(k,b.getLocation().add(.5,.5,.5));s.advance();send(p,"Saved "+k+". Next: "+s.nextKey());
        saveSetup();
    }

    private void saveSetup(){
        org.bukkit.configuration.file.YamlConfiguration y=new org.bukkit.configuration.file.YamlConfiguration();
        for(var e:points.entrySet())y.set("points."+e.getKey(),serialize(e.getValue()));
        for(var e:regions.entrySet()){y.set("regions."+e.getKey()+".a",serialize(e.getValue().a));y.set("regions."+e.getKey()+".b",serialize(e.getValue().b));}
        try{y.save(new File(getDataFolder(),"setup.yml"));}catch(Exception ignored){}
    }

    private void loadSetup(){
        File f=new File(getDataFolder(),"setup.yml");if(!f.exists())return;
        org.bukkit.configuration.file.YamlConfiguration y=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        if(y.getConfigurationSection("points")!=null)for(String k:y.getConfigurationSection("points").getKeys(false)){Location l=deserialize(y.getString("points."+k));if(l!=null)points.put(k,l);}
        if(y.getConfigurationSection("regions")!=null)for(String k:y.getConfigurationSection("regions").getKeys(false)){Location a=deserialize(y.getString("regions."+k+".a")),b=deserialize(y.getString("regions."+k+".b"));if(a!=null&&b!=null)regions.put(k,new Region(a,b));}
    }

    private String serialize(Location l){return l.getWorld().getName()+"|"+l.getX()+"|"+l.getY()+"|"+l.getZ()+"|"+l.getYaw()+"|"+l.getPitch();}
    private Location deserialize(String s){try{String[]a=s.split("\\|");World w=Bukkit.getWorld(a[0]);return new Location(w,Double.parseDouble(a[1]),Double.parseDouble(a[2]),Double.parseDouble(a[3]),Float.parseFloat(a[4]),Float.parseFloat(a[5]));}catch(Exception e){return null;}}

    @EventHandler public void onJoin(PlayerJoinEvent e){
        Player p=e.getPlayer();refreshName(p);
        if(players.containsKey(p.getUniqueId())) {
            P d=players.get(p.getUniqueId());
            if(d.state==State.ACTIVE||d.state==State.ELIMINATED){p.setGameMode(d.state==State.ACTIVE?GameMode.SURVIVAL:GameMode.SPECTATOR);}
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e){
        if(active(e.getPlayer()) && eventStarted) {
            // Keep the number/state; rejoining returns to the same active contestant.
            sendAll(playerLabel(e.getPlayer().getUniqueId())+" disconnected. Their number is reserved.");
        }
    }

    @EventHandler public void onDeath(PlayerDeathEvent e){
        if(active(e.getEntity()) && game!=Game.NONE) eliminate(e.getEntity(),"died");
    }

    @EventHandler public void onMove(PlayerMoveEvent e){
        Player p=e.getPlayer();
        if(game==Game.RLGL && rlglRed && active(p) && !near(e.getFrom(),e.getTo(),0.001) && System.currentTimeMillis()>rlglTransitionUntil){
            Location a=e.getFrom(),b=e.getTo();
            if(Math.hypot(a.getX()-b.getX(),a.getZ()-b.getZ())>getConfig().getDouble("games.rlgl.movement-tolerance",0.10))eliminate(p,"moved on Red Light");
        }
        if(game==Game.GLASSBRIDGE && active(p) && !p.getUniqueId().equals(currentGlassPlayer)){
            Location target=point("glassbridge."+Math.max(1,glassCurrent.getOrDefault(currentGlassPlayer,0)+1)+".left");
            if(target!=null && p.getLocation().distanceSquared(target)>36) {
                p.teleport(e.getFrom());
            }
        }
    }

    @EventHandler public void onInteract(PlayerInteractEvent e){
        Player p=e.getPlayer();
        ItemStack item=e.getItem();
        if(item!=null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(setupKey,PersistentDataType.STRING)
                && (e.getAction()==Action.LEFT_CLICK_BLOCK||e.getAction()==Action.RIGHT_CLICK_BLOCK)){
            e.setCancelled(true); setupClick(p,e.getClickedBlock(),e.getAction()==Action.RIGHT_CLICK_BLOCK); return;
        }
        if(game==Game.TUGOFWAR && active(p) && (e.getAction()==Action.LEFT_CLICK_AIR||e.getAction()==Action.LEFT_CLICK_BLOCK||e.getAction()==Action.RIGHT_CLICK_AIR||e.getAction()==Action.RIGHT_CLICK_BLOCK)){
            tugClick(p);
        }
    }

    @EventHandler public void onInteractEntity(PlayerInteractEntityEvent e){
        Player p=e.getPlayer(); if(!(e.getRightClicked() instanceof Player target))return;
        if(!eventStarted || game!=Game.NONE || !active(p) || !active(target) || p.equals(target))return;
        if(marblePairs.containsKey(p.getUniqueId())||marblePairs.containsKey(target.getUniqueId())){send(p,"One of you is already paired.");return;}
        marbleRequests.put(target.getUniqueId(),p.getUniqueId());
        send(target,ChatColor.YELLOW+playerLabel(p.getUniqueId())+" wants to be your Marbles partner.");
        send(target,ChatColor.GREEN+"Click them again to ACCEPT, or sneak-right-click them to DECLINE.");
    }

    @EventHandler public void onBlockPlace(BlockPlaceEvent e){ if(game==Game.DALGONA) e.setCancelled(false); }
    @EventHandler public void onBlockBreak(BlockBreakEvent e){
        if(game==Game.DALGONA && active(e.getPlayer())){
            for(int i=1;i<=4;i++){Region r=region("dalgona."+i+".build");if(r!=null&&r.contains(e.getBlock().getLocation()))return;}
            e.setCancelled(true);
        }
    }

    @EventHandler public void onInv(InventoryClickEvent e){}

    private void sendAll(String s){broadcast(s);}

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a){
        if(!c.getName().equalsIgnoreCase("sg"))return false;
        if(a.length==0){help(s);return true;}
        String sub=a[0].toLowerCase();
        if(sub.equals("queue")){
            if(!(s instanceof Player p)){return true;}
            if(eventStarted){send(p,"Event already in progress.");return true;}
            int max=getConfig().getInt("queue.maximum-players",100);
            if(queue.size()>=max){send(p,"Queue is full.");return true;}
            P d=players.get(p.getUniqueId());
            if(d!=null && (d.state==State.ACTIVE||d.state==State.ELIMINATED)){send(p,"You cannot rejoin this event.");return true;}
            if(d==null){d=new P(p.getUniqueId(),newNumber(),State.WAITING);players.put(d.id,d);}
            queue.add(p.getUniqueId());send(p,"Joined the queue as "+String.format("%03d",d.number)+". Queue: "+queue.size()+"/"+max);refreshName(p);saveData();return true;
        }
        if(sub.equals("start")){
            if(!isFrontman(s)){s.sendMessage("Frontman only.");return true;}
            if(a.length==1){startEvent();return true;}
            Game g=parseGame(a[1]);if(g==null){s.sendMessage("Unknown game.");return true;}startGame(g);return true;
        }
        if(sub.equals("stop")){if(isFrontman(s))stopGame();return true;}
        if(sub.equals("status")){status(s);return true;}
        if(sub.equals("lobby")){
            if(!(s instanceof Player p))return true;
            P d=players.get(p.getUniqueId());
            if(eventStarted && d!=null && (d.state==State.ACTIVE||d.state==State.ELIMINATED)){send(p,"You cannot leave the Squid Game once the event has started.");return true;}
            queue.remove(p.getUniqueId()); normalLobby(p);return true;
        }
        if(sub.equals("players")&&a.length>1&&a[1].equalsIgnoreCase("list")){playerList(s);return true;}
        if(sub.equals("eliminate")&&a.length>1&&isFrontman(s)){Player p=Bukkit.getPlayerExact(a[1]);if(p!=null)eliminate(p,"Frontman");return true;}
        if(sub.equals("spectator")&&a.length>1&&isFrontman(s)){Player p=Bukkit.getPlayerExact(a[1]);if(p!=null)spectator(p);return true;}
        if(sub.equals("staff")&&a.length>2&&isFrontman(s)){
            Player p=Bukkit.getPlayerExact(a[2]);
            if(p==null){s.sendMessage("Player not online.");return true;}
            if(a[1].equalsIgnoreCase("add")&&a.length>3){if(a[3].equalsIgnoreCase("frontman")){frontmen.add(p.getUniqueId());guards.remove(p.getUniqueId());}else if(a[3].equalsIgnoreCase("guard")){guards.add(p.getUniqueId());frontmen.remove(p.getUniqueId());}refreshName(p);saveData();return true;}
            if(a[1].equalsIgnoreCase("remove")){frontmen.remove(p.getUniqueId());guards.remove(p.getUniqueId());refreshName(p);saveData();return true;}
        }
        if(sub.equals("setup")){
            if(!isFrontman(s)){s.sendMessage("Frontman only.");return true;}
            if(a.length==2&&a[1].equalsIgnoreCase("status")){setupStatus(s);return true;}
            if(a.length>=2){if(s instanceof Player p){beginSetup(p,a[1].toLowerCase());}return true;}
        }
        if(sub.equals("event")&&a.length>1&&a[1].equalsIgnoreCase("reset")&&isFrontman(s)){eventReset();return true;}
        if(sub.equals("reset")&&a.length>1&&isFrontman(s)){Game g=parseGame(a[1]);if(g!=null)resetGame(g);return true;}
        if(sub.equals("reload")&&isFrontman(s)){reloadConfig();loadSetup();s.sendMessage("Reloaded.");return true;}
        if(sub.equals("book")&&s instanceof Player p&&isFrontman(s)){book(p);return true;}
        if(sub.equals("lobby")&&s instanceof Player p){normalLobby(p);return true;}
        return true;
    }

    private Game parseGame(String s){return switch(s.toLowerCase()){case"rlgl"->Game.RLGL;case"dalgona"->Game.DALGONA;case"nightfight"->Game.NIGHTFIGHT;case"tugofwar"->Game.TUGOFWAR;case"marbles"->Game.MARBLES;case"glassbridge"->Game.GLASSBRIDGE;case"squidgame"->Game.SQUIDGAME;default->null;};}

    private void help(CommandSender s){
        s.sendMessage(ChatColor.GOLD+"===== SPLASHHYS SQUID GAME =====");
        s.sendMessage("/sg queue");
        s.sendMessage("/sg lobby");
        s.sendMessage("/sg status");
        s.sendMessage("/sg players list");
        if(isFrontman(s)){
            s.sendMessage("/sg start");
            s.sendMessage("/sg start <rlgl|dalgona|nightfight|tugofwar|marbles|glassbridge|squidgame>");
            s.sendMessage("/sg stop");
            s.sendMessage("/sg setup <game>");
            s.sendMessage("/sg setup status");
            s.sendMessage("/sg eliminate <player>");
            s.sendMessage("/sg spectator <player>");
            s.sendMessage("/sg staff add <player> <frontman|guard>");
            s.sendMessage("/sg staff remove <player>");
            s.sendMessage("/sg event reset");
            s.sendMessage("/sg reset <game>");
            s.sendMessage("/sg book");
            s.sendMessage("/sg reload");
        }
    }

    private void status(CommandSender s){
        s.sendMessage(ChatColor.GOLD+"===== SQUID GAME STATUS =====");
        s.sendMessage("Event: "+(eventStarted?"STARTED":"WAITING"));
        s.sendMessage("Game: "+game.name());
        s.sendMessage("Queue: "+queue.size()+"/"+getConfig().getInt("queue.maximum-players",100));
        s.sendMessage("Active: "+activeCount());
        s.sendMessage("Eliminated: "+eliminatedCount());
        s.sendMessage("Frontmen: "+frontmen.size()+" | Guards: "+guards.size());
    }

    private void playerList(CommandSender s){
        List<P> list=new ArrayList<>(players.values());list.sort(Comparator.comparingInt(x->x.number));
        s.sendMessage(ChatColor.GOLD+"===== SQUID GAME PLAYERS =====");
        for(State st:List.of(State.ACTIVE,State.ELIMINATED,State.WAITING,State.SPECTATOR)){
            long count=list.stream().filter(x->x.state==st).count();if(count==0)continue;
            s.sendMessage(ChatColor.YELLOW+st.name()+" ("+count+")");
            for(P p:list)if(p.state==st)s.sendMessage("["+String.format("%03d",p.number)+"] "+Optional.ofNullable(Bukkit.getPlayer(p.id)).map(Player::getName).orElse(p.id.toString().substring(0,8)));
        }
    }

    private void setupStatus(CommandSender s){
        for(Game g:List.of(Game.RLGL,Game.DALGONA,Game.NIGHTFIGHT,Game.TUGOFWAR,Game.MARBLES,Game.GLASSBRIDGE,Game.SQUIDGAME))
            s.sendMessage(g.name()+": "+(ready(g)?ChatColor.GREEN+"READY":ChatColor.RED+"MISSING"));
    }

    private void eventReset(){
        cancelTasks();eventStarted=false;game=Game.NONE;queue.clear();marbleRequests.clear();marblePairs.clear();marbleCount.clear();glassOrder.clear();glassCurrent.clear();tugTeam.clear();dalgonaTeams.clear();
        for(P p:players.values())p.state=State.WAITING;
        for(Player p:Bukkit.getOnlinePlayers()){p.setGameMode(GameMode.SURVIVAL);p.removePotionEffect(PotionEffectType.DARKNESS);p.removePotionEffect(PotionEffectType.NIGHT_VISION);refreshName(p);}
        players.clear();saveData();broadcast("Event reset. Map setup and staff roles were preserved.");
    }

    private void resetGame(Game g){
        if(game==g)stopGame();
        if(g==Game.GLASSBRIDGE) { for(int i=1;i<=12;i++) glassSafeLeft.remove(i); }
        broadcast(g.name()+" reset.");
    }

    private int newNumber(){
        int min=getConfig().getInt("player-numbers.minimum",2),max=getConfig().getInt("player-numbers.maximum",456);
        Set<Integer> used=new HashSet<>();for(P p:players.values())used.add(p.number);
        List<Integer> free=new ArrayList<>();for(int i=min;i<=max;i++)if(i!=1&&!used.contains(i))free.add(i);
        if(free.isEmpty())return min;
        return free.get(ThreadLocalRandom.current().nextInt(free.size()));
    }

    private void normalLobby(Player p){
        String wn=getConfig().getString("lobby.normal-return-world","world");World w=Bukkit.getWorld(wn);
        if(w!=null)p.teleport(w.getSpawnLocation());
        p.setGameMode(GameMode.SURVIVAL);
    }

    private void book(Player p){
        send(p,"FRONTMAN BOOK");
        p.sendMessage(ChatColor.GOLD+"/sg start rlgl");
        p.sendMessage(ChatColor.GOLD+"/sg start dalgona");
        p.sendMessage(ChatColor.GOLD+"/sg start nightfight");
        p.sendMessage(ChatColor.GOLD+"/sg start tugofwar");
        p.sendMessage(ChatColor.GOLD+"/sg start marbles");
        p.sendMessage(ChatColor.GOLD+"/sg start glassbridge");
        p.sendMessage(ChatColor.GOLD+"/sg start squidgame");
        p.sendMessage(ChatColor.RED+"/sg stop");
        p.sendMessage(ChatColor.YELLOW+"/sg players list");
        p.sendMessage(ChatColor.YELLOW+"/sg setup status");
        p.sendMessage(ChatColor.YELLOW+"/sg event reset");
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a){
        if(a.length==1)return List.of("queue","lobby","status","players","start","stop","setup","eliminate","spectator","staff","event","reset","book","reload");
        if(a.length==2&&a[0].equalsIgnoreCase("start"))return List.of("rlgl","dalgona","nightfight","tugofwar","marbles","glassbridge","squidgame");
        if(a.length==2&&a[0].equalsIgnoreCase("setup"))return List.of("rlgl","dalgona","nightfight","tugofwar","marbles","glassbridge","squidgame","status");
        if(a.length==2&&a[0].equalsIgnoreCase("reset"))return List.of("rlgl","dalgona","nightfight","tugofwar","marbles","glassbridge","squidgame");
        return List.of();
    }

    private static final class P {
        final UUID id; final int number; State state;
        P(UUID id,int number,State state){this.id=id;this.number=number;this.state=state;}
    }

    private static final class DalgonaTeam {
        final String name; final List<UUID> players; boolean finished=false; Snapshot reference;
        DalgonaTeam(String n,List<UUID> p){name=n;players=p;}
    }

    private static final class SetupSession {
        final String game; int index=0; Location temp1;
        SetupSession(String g){game=g;}
        String nextKey(){
            String[] keys=switch(game){
                case"rlgl"->new String[]{"rlgl.start","rlgl.finish","rlgl.doll"};
                case"nightfight"->new String[]{"nightfight.spawn","nightfight.area.corner1","nightfight.area.corner2"};
                case"tugofwar"->new String[]{"tugofwar.red","tugofwar.blue","tugofwar.red_edge","tugofwar.blue_edge"};
                case"marbles"->new String[]{"marbles.spectator","marbles.arena"};
                case"squidgame"->new String[]{"squidgame.spawn","squidgame.area.corner1","squidgame.area.corner2"};
                case"glassbridge"->{
                    List<String>x=new ArrayList<>(List.of("glassbridge.entrance","glassbridge.exit"));
                    for(int i=1;i<=12;i++){x.add("glassbridge."+i+".left");x.add("glassbridge."+i+".right");} yield x.toArray(String[]::new);
                }
                case"dalgona"->{
                    List<String>x=new ArrayList<>();
                    for(int i=1;i<=4;i++){x.add("dalgona."+i+".spawn");x.add("dalgona."+i+".reference.corner1");x.add("dalgona."+i+".reference.corner2");x.add("dalgona."+i+".build.corner1");x.add("dalgona."+i+".build.corner2");}
                    yield x.toArray(String[]::new);
                }
                default->new String[0];
            };
            return index<keys.length?keys[index]:null;
        }
        void advance(){index++;}
    }

    private static final class Region {
        final Location a,b;
        Region(Location a,Location b){this.a=a.clone();this.b=b.clone();}
        boolean contains(Location l){
            if(!a.getWorld().equals(l.getWorld()))return false;
            return l.getBlockX()>=minX()&&l.getBlockX()<=maxX()&&l.getBlockY()>=minY()&&l.getBlockY()<=maxY()&&l.getBlockZ()>=minZ()&&l.getBlockZ()<=maxZ();
        }
        int minX(){return Math.min(a.getBlockX(),b.getBlockX());} int maxX(){return Math.max(a.getBlockX(),b.getBlockX());}
        int minY(){return Math.min(a.getBlockY(),b.getBlockY());} int maxY(){return Math.max(a.getBlockY(),b.getBlockY());}
        int minZ(){return Math.min(a.getBlockZ(),b.getBlockZ());} int maxZ(){return Math.max(a.getBlockZ(),b.getBlockZ());}
        Snapshot snapshot(){return new Snapshot(this);}
    }

    private static final class Snapshot {
        final int minX,minY,minZ,maxX,maxY,maxZ; final Material[][][] mats;
        Snapshot(Region r){
            minX=r.minX();minY=r.minY();minZ=r.minZ();maxX=r.maxX();maxY=r.maxY();maxZ=r.maxZ();
            mats=new Material[maxX-minX+1][maxY-minY+1][maxZ-minZ+1];
            World w=r.a.getWorld();
            for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++)for(int z=minZ;z<=maxZ;z++)mats[x-minX][y-minY][z-minZ]=w.getBlockAt(x,y,z).getType();
        }
        boolean matches(Region r){
            if(r.maxX()-r.minX()!=maxX-minX||r.maxY()-r.minY()!=maxY-minY||r.maxZ()-r.minZ()!=maxZ-minZ)return false;
            World w=r.a.getWorld();
            for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++)for(int z=minZ;z<=maxZ;z++)
                if(w.getBlockAt(r.minX()+x-minX,r.minY()+y-minY,r.minZ()+z-minZ).getType()!=mats[x-minX][y-minY][z-minZ])return false;
            return true;
        }
    }
}
