import * as cheerio from "cheerio";

const ORIGIN = "https://mlbbhub.com";
const ID_PATTERN = /^[a-z0-9-]+$/i;

function clean(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function absoluteUrl(value) {
  if (!value) return "";
  try {
    return new URL(value, ORIGIN).toString();
  } catch {
    return "";
  }
}

function titleFromSlug(slug) {
  return String(slug || "")
    .split("-")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function firstMatch(text, patterns, group = 1) {
  for (const pattern of patterns) {
    const match = text.match(pattern);
    if (match && match[group] != null) return clean(match[group]);
  }
  return "";
}

function percent(text, label) {
  const value = firstMatch(text, [
    new RegExp(label + "\\s+(\\d+(?:\\.\\d+)?)\\s*%", "i"),
    new RegExp("(\\d+(?:\\.\\d+)?)\\s*%\\s*" + label, "i")
  ]);
  return value === "" ? null : Number(value);
}

function parseOverviewStats(bodyText) {
  const glance = firstMatch(bodyText, [
    /At a Glance\s+(.+?)\s+Durability/i
  ]);

  const source = glance || bodyText;

  return {
    win_rate: percent(source, "Win Rate"),
    pick_rate: percent(source, "Pick Rate"),
    ban_rate: percent(source, "Ban Rate")
  };
}

function normalizeLane(rawValue) {
  const raw = clean(rawValue);

  if (!raw) return "";

  const found = [];

  function add(value) {
    if (!found.includes(value)) found.push(value);
  }

  if (/\b(jungle lane|jungler|jungle)\b/i.test(raw)) {
    add("Jungle");
  }

  if (/\b(exp lane|exp laner)\b/i.test(raw)) {
    add("EXP Lane");
  }

  if (/\b(gold lane|gold laner)\b/i.test(raw)) {
    add("Gold Lane");
  }

  if (/\b(mid lane|mid laner|middle lane)\b/i.test(raw)) {
    add("Mid Lane");
  }

  if (/\b(roam|roamer)\b/i.test(raw)) {
    add("Roam");
  }

  if (found.length > 0) {
    return found.join(" • ");
  }

  return raw
    .replace(/\blane\s+laner\b/gi, "Lane")
    .replace(/\s*[/|,]\s*/g, " • ")
    .replace(/\s*•\s*/g, " • ")
    .trim();
}

function parseRole(bodyText) {
  return firstMatch(bodyText, [
    /Combat Class\s+(Assassin|Fighter|Tank|Mage|Marksman|Support)\b/i,
    /Tier\s+[SABCD]\s+(Assassin|Fighter|Tank|Mage|Marksman|Support)\b/i,
    /\bis an?\s+(Assassin|Fighter|Tank|Mage|Marksman|Support)\s+hero\b/i
  ]);
}

function parseLane(bodyText) {
  const raw = firstMatch(bodyText, [
    /Lane\s+(.+?)\s+Specialty/i,
    /Tier\s+[SABCD]\s+(?:Assassin|Fighter|Tank|Mage|Marksman|Support)\s+(.+?)\s+(?:Easy|Medium|Hard)\b/i,
    /\busually played in the\s+(.+?)(?:\.|,)/i
  ]);

  return normalizeLane(raw);
}

function getImageFromAnchor($, anchor) {
  const img = anchor.find("img").first();
  return absoluteUrl(
    img.attr("src") ||
    img.attr("data-src") ||
    img.attr("data-lazy-src") ||
    img.attr("srcset")
  );
}

function findAnchorByText($, name) {
  let result = null;
  $("a").each((_, node) => {
    if (result) return false;
    const anchor = $(node);
    if (clean(anchor.text()).toLowerCase() === name.toLowerCase()) {
      result = anchor;
      return false;
    }
  });
  return result;
}

function makeLinkedEntry($, name) {
  const anchor = findAnchorByText($, name);
  return {
    name,
    image: anchor ? getImageFromAnchor($, anchor) : "",
    url: anchor ? absoluteUrl(anchor.attr("href")) : ""
  };
}

function parseBuild($, bodyText) {
  const section = firstMatch(bodyText, [
    /Aamon Best Builds, Emblems & Talents\s+(.+?)\s+02\s+Enhanced Basic DPS/i,
    /Best Builds, Emblems & Talents\s+(.+?)\s+02\s+/i,
    /Curated Build\s+(.+?)\s+Counter Picks/i
  ]);

  const source = section || bodyText;

  const name = firstMatch(source, [
    /01\s+([A-Za-z0-9 '&-]+?)\s+Default jungle/i,
    /Curated Build\s+([A-Za-z0-9 '&-]+?)\s+See all/i,
    /([A-Za-z0-9 '&-]+?)\s+Default Path/i
  ]) || "Recommended Build";

  const description = firstMatch(source, [
    /Jungle Burst Default\s+(.+?)\s+Cost\s+[\d,]+\s*g/i,
    /Recommended Build\s+(.+?)\s+Cost\s+[\d,]+\s*g/i
  ]);

  const cost = firstMatch(source, [
    /Cost\s+([\d,]+)\s*g/i,
    /Default Path\s+([\d,]+)\s*g/i
  ]).replace(/,/g, "");

  const itemBlock = firstMatch(source, [
    /Cost\s+[\d,]+\s*g\s+Simulator\s+(.+?)\s+Magic Power/i,
    /Default Path\s+[\d,]+\s*g\s+(.+?)\s+Emblem/i
  ]);

  const knownItems = [
    "Arcane Boots", "Genius Wand", "Holy Crystal", "Divine Glaive",
    "Blood Wings", "Winter Crown", "Feather of Heaven",
    "Starlium Scythe", "Sky Piercer", "Glowing Wand",
    "Tough Boots", "Immortality"
  ];

  const itemNames = knownItems
    .filter((name) => new RegExp("\\b" + name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\b", "i")
      .test(itemBlock || source))
    .slice(0, 6);

  const items = itemNames.map((name) => makeLinkedEntry($, name));

  const emblem = firstMatch(source, [
    /Emblem Setup\s+((?:Assassin|Mage|Fighter|Tank|Marksman|Support|Common)\s+Emblem)/i,
    /Emblem\s+((?:Assassin|Mage|Fighter|Tank|Marksman|Support|Common)\s+Emblem)/i
  ]);

  const knownTalents = [
    "Rupture", "Master Assassin", "Killing Spree", "Inspire",
    "Bargain Hunter", "Lethal Ignition", "Agility",
    "Weapons Master", "Quantum Charge", "Festival of Blood",
    "Brave Smite", "Concussive Blast"
  ];

  const talentArea = firstMatch(source, [
    /Emblem Setup\s+.+?Emblem\s+(.+?)\s+Battle Spell/i,
    /Emblem\s+.+?Emblem\s+(.+?)\s+Spell/i
  ]);

  const talents = knownTalents.filter((talent) =>
    talentArea.toLowerCase().includes(talent.toLowerCase())
  );

  const spell = firstMatch(source, [
    /Battle Spell\s+([A-Za-z ]+?)\s+Blessing/i,
    /Spell\s+([A-Za-z ]+?)(?:\s+Counter Picks|\s*$)/i
  ]);

  const blessing = firstMatch(source, [
    /Blessing\s+([A-Za-z ]+?)(?:\s+Situational|\s+02\s+|\s*$)/i
  ]);

  let spellImage = "";
  $("img").each((_, node) => {
    if (spellImage) return false;
    const img = $(node);
    const alt = clean(img.attr("alt"));
    if (spell && alt.toLowerCase().includes(spell.toLowerCase())) {
      spellImage = absoluteUrl(
        img.attr("src") ||
        img.attr("data-src") ||
        img.attr("data-lazy-src")
      );
      return false;
    }
  });

  return {
    name,
    description,
    cost,
    items,
    emblem,
    talents,
    spell,
    spell_image: spellImage,
    blessing
  };
}

function parseHeroGroup($, text, pattern, currentId) {
  const block = firstMatch(text, [pattern]);
  if (!block) return [];

  const candidates = [];
  $("a[href*='/heroes/']").each((_, node) => {
    const anchor = $(node);
    const href = absoluteUrl(anchor.attr("href"));
    let url;
    try { url = new URL(href); } catch { return; }

    const parts = url.pathname.split("/").filter(Boolean);
    if (parts.length !== 2 || parts[0] !== "heroes") return;

    const id = parts[1].toLowerCase();
    if (id === currentId) return;

    const name = clean(anchor.text())
      .replace(/\b[SABCD]\b/g, "")
      .replace(/\d+(?:\.\d+)?\s*%.*$/i, "")
      .replace(/\b(Assassin|Fighter|Tank|Mage|Marksman|Support)\b/gi, "")
      .trim();

    if (!name || !new RegExp("\\b" + name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\b", "i").test(block)) {
      return;
    }

    if (candidates.some((x) => x.id === id)) return;

    const raw = clean(anchor.text());
    const tier = firstMatch(raw, [/\b([SABCD])\b/i]);
    const wr = firstMatch(block, [
      new RegExp(name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\s+(\\d+(?:\\.\\d+)?)\\s*%", "i")
    ]);

    candidates.push({
      id,
      name,
      tier,
      role: firstMatch(raw, [/\b(Assassin|Fighter|Tank|Mage|Marksman|Support)\b/i]),
      win_rate: wr ? Number(wr) : null,
      image: getImageFromAnchor($, anchor),
      details_url: `${ORIGIN}/heroes/${id}`
    });
  });

  return candidates.slice(0, 5);
}

async function fetchHtml(url) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 20000);

  try {
    const response = await fetch(url, {
      headers: {
        "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9"
      },
      signal: controller.signal
    });

    if (!response.ok) throw new Error(`MLBBHub returned HTTP ${response.status}`);
    return await response.text();
  } finally {
    clearTimeout(timeout);
  }
}

export default async function handler(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({
      success: false,
      message: "Method not allowed. Use GET."
    });
  }

  const rawId = Array.isArray(req.query.id) ? req.query.id[0] : req.query.id;
  const id = clean(rawId).toLowerCase();

  if (!id || !ID_PATTERN.test(id)) {
    return res.status(400).json({
      success: false,
      message: "A valid hero id is required.",
      example: "/api/hero?id=aamon"
    });
  }

  try {
    const source = `${ORIGIN}/heroes/${id}`;
    const html = await fetchHtml(source);
    const $ = cheerio.load(html);
    const bodyText = clean($("body").text());

    const name = firstMatch(bodyText, [
      /Codename\s+([A-Za-z0-9 '&.-]+?)\s+[“"]/i,
      /Subject Codename\s+([A-Za-z0-9 '&.-]+?)\s+[“"]/i
    ]) || titleFromSlug(id);

    const title = firstMatch(bodyText, [
      new RegExp(name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\s+[“\"]([^”\"]+)[”\"]", "i")
    ]);

    const role = parseRole(bodyText);
    const lane = parseLane(bodyText);

    const specialty = firstMatch(bodyText, [
      /Specialty\s+(.+?)\s+Difficulty/i
    ]).replace(/\s*·\s*/g, " · ");

    const difficulty = firstMatch(bodyText, [
      /Difficulty\s+(Easy|Medium|Hard)/i
    ]);

    const tier = firstMatch(bodyText, [
      /Tier\s+([SABCD])\b/i
    ]).toUpperCase();

    let overview = "";
    $("p").each((_, node) => {
      if (overview) return false;
      const text = clean($(node).text());
      if (
        text.length >= 100 &&
        new RegExp("\\b" + name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\b", "i").test(text) &&
        !/cookie|privacy|advertisement|fan-made|not affiliated/i.test(text)
      ) {
        overview = text;
        return false;
      }
    });

    let image = "";
    $("img").each((_, node) => {
      if (image) return false;
      const img = $(node);
      const alt = clean(img.attr("alt"));
      if (
        new RegExp("\\b" + name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\b", "i").test(alt) &&
        /Mobile Legends|hero/i.test(alt)
      ) {
        image = absoluteUrl(
          img.attr("src") ||
          img.attr("data-src") ||
          img.attr("data-lazy-src")
        );
        return false;
      }
    });

    const backdrop = absoluteUrl($('meta[property="og:image"]').attr("content"));

    const counters = parseHeroGroup(
      $,
      bodyText,
      new RegExp("How to Counter\\s+" + name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\s+(.+?)\\s+Heroes\\s+" + name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\s+Counters", "i"),
      id
    );

    const strongAgainst = parseHeroGroup(
      $,
      bodyText,
      new RegExp("Heroes\\s+" + name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\s+Counters\\s+(.+?)\\s+Game Phase Analysis", "i"),
      id
    );

    const build = parseBuild($, bodyText);
    const overviewStats = parseOverviewStats(bodyText);

    res.setHeader("Cache-Control", "public, s-maxage=3600, stale-while-revalidate=86400");
    res.setHeader("Access-Control-Allow-Origin", "*");

    return res.status(200).json({
      success: true,
      source,
      fetched_at: new Date().toISOString(),
      hero: {
        id,
        name,
        title,
        role,
        lane,
        specialty,
        difficulty,
        tier,
        win_rate: overviewStats.win_rate,
        pick_rate: overviewStats.pick_rate,
        ban_rate: overviewStats.ban_rate,
        overview,
        image: image || backdrop,
        backdrop,
        build,
        counters,
        strong_against: strongAgainst,
        skills: []
      }
    });
  } catch (error) {
    console.error("Hero endpoint error:", error);
    return res.status(500).json({
      success: false,
      message: "Failed to retrieve the hero guide.",
      error: error instanceof Error ? error.message : String(error)
    });
  }
}
