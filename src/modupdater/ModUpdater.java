package modupdater;

import arc.files.*;
import arc.func.*;
import arc.graphics.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Http.*;
import arc.util.async.*;
import arc.util.serialization.*;
import arc.util.serialization.Jval.*;

import javax.imageio.*;
import java.awt.image.*;
import java.util.*;

import static arc.struct.StringMap.*;

public class ModUpdater{
    static final String api = "https://api.github.com", searchTerm = "mindustry mod";
    static final int perPage = 150;
    static final int maxLength = 55;
    static final ObjectSet<String> javaLangs = ObjectSet.with("Java", "Kotlin", "Groovy"); //obviously not a comprehensive list
    static final ObjectSet<String> blacklist = ObjectSet.with("TheSaus/Cumdustry", "Anuken/ExampleMod", "Anuken/ExampleJavaMod", "Anuken/ExampleKotlinMod","pixaxeofpixie/Braindustry-Mod","MoTRona/Colloseus-Mod");
    static final int iconSize = 64;
	
	static final String githubToken = OS.prop("githubtoken");
	
    public static void main(String[] args){
        new ModUpdater();
    }

    {
        //register colors to facilitate their removal
        Colors.put("accent", Color.white);
        Colors.put("unlaunched",  Color.white);
        Colors.put("highlight",  Color.white);
        Colors.put("stat",  Color.white);

        query("/search/repositories", of("q", searchTerm, "per_page", perPage), result -> {
            int total = result.getInt("total_count", 0);
            int pages = Mathf.ceil((float)total / perPage);

            for(int i = 1; i < pages; i++){
                query("/search/repositories", of("q", searchTerm, "per_page", perPage, "page", i + 1), secresult -> {
                    result.get("items").asArray().addAll(secresult.get("items").asArray());
                });
            }

            for(String topic : new String[]{"mindustry-mod", "mindustry-mod-v6"}){
                query("/search/repositories", of("q", "topic:" + topic, "per_page", perPage), topicresult -> {
                    Seq<Jval> dest = result.get("items").asArray();
                    Seq<Jval> added = topicresult.get("items").asArray().select(v -> !dest.contains(o -> o.get("full_name").equals(v.get("full_name"))));
                    dest.addAll(added);

                    Log.info("\n&lcFound @ mods via topic: " + topic, added.size);
                });
            }

            ObjectMap<String, Jval> output = new ObjectMap<>();
            ObjectMap<String, Jval> ghmeta = new ObjectMap<>();
            Seq<String> names = result.get("items").asArray().map(val -> {
                ghmeta.put(val.get("full_name").toString(), val);
                return val.get("full_name").toString();
            });

            for(String name : blacklist){
                names.remove(name);
            }

            Fi icons = Fi.get("icons");

            icons.delete(); //.deleteDirectory();
            icons.mkdirs();

            Log.info("&lcTotal mods found: @\n", names.size);

            int index = 0;
            for(String name : names){
                Log.info("&lc[@%] [@]&y: querying...", (int)((float)index++ / names.size * 100), name);

                try{
                    Jval meta = ghmeta.get(name);
                    String branch = meta.getString("default_branch");
                    Jval modjson = tryList(name + "/" + branch + "/mod.json", name + "/" + branch + "/mod.hjson", name + "/" + branch + "/assets/mod.json", name + "/" + branch + "/assets/mod.hjson");

                    if(modjson == null){
                        Log.info("&lc| &lySkipping, no meta found.");
                        continue;
                    }

                    //filter icons based on stars to prevent potential abuse
                    if(meta.getInt("stargazers_count", 0) >= 2){
                        var icon = tryImage(name + "/" + branch + "/icon.png", name + "/" + branch + "/assets/icon.png");
                        if(icon != null){
                            var scaled = new BufferedImage(iconSize, iconSize, BufferedImage.TYPE_INT_ARGB);
                            scaled.createGraphics().drawImage(icon.getScaledInstance(iconSize, iconSize, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, iconSize, iconSize, null);
                            Log.info("&lc| &lmFound icon file: @x@", icon.getWidth(), icon.getHeight());
                            ImageIO.write(scaled, "png", icons.child(name.replace("/", "_")+".png").file());
                        }
                    }

                    Log.info("&lc|&lg Found mod meta file!");
                    output.put(name, modjson);
                }catch(Throwable t){
                    Log.info("&lc| &lySkipping. [@]", name, Strings.getSimpleMessage(t));
                }
            }

            Log.info("&lcFound @ valid mods.", output.size);
            Seq<String> outnames = output.keys().toSeq();
            outnames.sort(Structs.comps(Comparator.comparingInt(s -> -ghmeta.get(s).getInt("stargazers_count", 0)), Structs.comparing(s -> ghmeta.get(s).getString("pushed_at"))));

            Log.info("&lcCreating mods.json file...");
            Jval array = Jval.read("[]");
            for(String name : outnames){
                Jval gm = ghmeta.get(name);
                Jval modj = output.get(name);
                Jval obj = Jval.read("{}");
                String displayName = Strings.stripColors(modj.getString("displayName", "")).replace("\\n", "");
                if(displayName.isEmpty()) displayName = gm.getString("name");

                //skip outdated mods
                String version = modj.getString("minGameVersion", "104");
                int minBuild = Strings.parseInt(version.contains(".") ? version.split("\\.")[0] : version, 0);
                if(minBuild < 105){
                    continue;
                }

                String lang = gm.getString("language", "");

                String metaName = Strings.stripColors(displayName).replace("\n", "");
                if(metaName.length() > maxLength) metaName = name.substring(0, maxLength) + "...";

                obj.add("repo", name);
                obj.add("name", metaName);
                obj.add("version", Strings.stripColors(modj.getString("version", "No version provided.")));
                obj.add("mod_id", Strings.stripColors(modj.getString("name", "No name provided.")));
                obj.add("author", Strings.stripColors(modj.getString("author", gm.get("owner").get("login").toString())));
                obj.add("lastUpdated", gm.get("pushed_at"));
                obj.add("stars", gm.get("stargazers_count"));
                obj.add("minGameVersion", version);
                obj.add("hasScripts", Jval.valueOf(lang.equals("JavaScript")));
                obj.add("hasJava", Jval.valueOf(modj.getBool("java", false) || javaLangs.contains(lang)));
                obj.add("description", Strings.stripColors(modj.getString("description", "No description provided.")));
                array.asArray().add(obj);
            }

            new Fi("mods.json").writeString(array.toString(Jformat.formatted));

            Log.info("&lcDone. Exiting.");
        });
    }

    Jval tryList(String... queries){
        Jval[] result = {null};
        for(String str : queries){
            //try to get mod.json instead
            Http.get("https://raw.githubusercontent.com/" + str)
				.error(this::simpleError)
            .block(out -> result[0] = Jval.read(out.getResultAsString()));
        }
        return result[0];
    }

    BufferedImage tryImage(String... queries){
        BufferedImage[] result = {null};
        for(String str : queries){
            //try to get mod.json instead
            Http.get("https://raw.githubusercontent.com/" + str)
				.error(this::simpleError)
            .block(out -> result[0] = ImageIO.read(out.getResultAsStream()));
        }
        return result[0];
    }

    void query(String url, @Nullable StringMap params, Cons<Jval> cons){
        Http.get(api + url + (params == null ? "" : "?" + params.keys().toSeq().map(entry -> Strings.encode(entry) + "=" + Strings.encode(params.get(entry))).toString("&")))
        .timeout(10000)
			.method(HttpMethod.GET)
			.header("authorization", githubToken)
			.header("accept", "application/vnd.github.baptiste-preview+json")
			.error(this::handleError)
        .block(response -> {
            Log.info("&lcSending search query. Status: @; Queries remaining: @/@", response.getStatus(), response.getHeader("X-RateLimit-Remaining"), response.getHeader("X-RateLimit-Limit"));
            cons.get(Jval.read(response.getResultAsString()));
        });
    }

    void simpleError(Throwable error){
        if(!(error instanceof HttpStatusException)){
            Log.info("&lc |&lr" + Strings.getSimpleMessage(error));
        }
    }

    void handleError(Throwable error){
        Log.err(error);
    }
}
