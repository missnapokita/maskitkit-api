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
  const match = bodyText.match(
    /At a Glance\s+.*?Win Rate\s+(\d+(?:\.\d+)?)\s*%\s+Pick Rate\s+(\d+(?:\.\d+)?)\s*%\s+Ban Rate\s+(\d+(?:\.\d+)?)\s*%\s+.*?Durability/i
  );

  if (match) {
    return {
      win_rate: Number(match[1]),
      pick_rate: Number(match[2]),
      ban_rate: Number(match[3])
    };
  }

  // FAQ fallback used when the main stats block changes.
  const faq = bodyText.match(
    /currently holds a\s+(\d+(?:\.\d+)?)\s*%\s+win rate,\s+a\s+(\d+(?:\.\d+)?)\s*%\s+pick rate,\s+and a\s+(\d+(?:\.\d+)?)\s*%\s+ban rate/i
  );

  if (faq) {
    return {
      win_rate: Number(faq[1]),
      pick_rate: Number(faq[2]),
      ban_rate: Number(faq[3])
    };
  }

  return {
    win_rate: null,
    pick_rate: null,
    ban_rate: null
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
    /Emblem Setup\s+((?:Assassin|Mage|Fighter|Tank|Marksman|Support|Common)\s+Emblem)\b/i,
    /Emblem\s+((?:Assassin|Mage|Fighter|Tank|Marksman|Support|Common)\s+Emblem)\b/i
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
    /Blessing\s+([A-Za-z ]+?)(?:\s+Situational Swaps|\s+02\s+|\s*$)/i
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



function escapeRegExp(value) {
  return String(value || "").replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function heroAnchorInfo($, heroName) {
  let result = {
    id: "",
    name: heroName,
    image: "",
    details_url: ""
  };

  $("a[href*='/heroes/']").each((_, node) => {
    const anchor = $(node);
    const text = clean(anchor.text());
    const img = anchor.find("img").first();
    const alt = clean(img.attr("alt"))
      .replace(/\s+hero icon$/i, "")
      .replace(/\s+hero$/i, "");

    if (
      text.toLowerCase() !== heroName.toLowerCase() &&
      alt.toLowerCase() !== heroName.toLowerCase()
    ) {
      return;
    }

    const href = absoluteUrl(anchor.attr("href"));

    try {
      const url = new URL(href);
      const parts = url.pathname.split("/").filter(Boolean);

      if (parts.length === 2 && parts[0] === "heroes") {
        result.id = parts[1].toLowerCase();
      }
    } catch {
      return;
    }

    result.image = absoluteUrl(
      img.attr("src") ||
      img.attr("data-src") ||
      img.attr("data-lazy-src")
    );

    result.details_url = href;
    return false;
  });

  return result;
}

function slugFromHeroName(name) {
  return clean(name)
    .toLowerCase()
    .replace(/&/g, "and")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

function findHeroAsset($, name, fallbackId) {
  let result = {
    id: fallbackId,
    image: "",
    details_url: `${ORIGIN}/heroes/${fallbackId}`
  };

  $("a[href*='/heroes/']").each((_, node) => {
    const anchor = $(node);
    const img = anchor.find("img").first();

    const anchorText = clean(anchor.text());
    const altText = clean(img.attr("alt"))
      .replace(/\s+hero icon$/i, "")
      .replace(/\s+hero$/i, "");

    if (
      anchorText.toLowerCase() !== name.toLowerCase() &&
      altText.toLowerCase() !== name.toLowerCase()
    ) {
      return;
    }

    const href = absoluteUrl(anchor.attr("href"));

    try {
      const parsed = new URL(href);
      const parts = parsed.pathname.split("/").filter(Boolean);

      if (parts.length === 2 && parts[0] === "heroes") {
        result.id = parts[1].toLowerCase();
      }
    } catch {
      // Keep the generated fallback id.
    }

    result.image = absoluteUrl(
      img.attr("src") ||
      img.attr("data-src") ||
      img.attr("data-lazy-src")
    );

    result.details_url = href || `${ORIGIN}/heroes/${result.id}`;
    return false;
  });

  return result;
}

function parseMatchupTextBlock($, bodyText, startPattern, endPattern, currentId) {
  const startMatch = bodyText.match(startPattern);

  if (!startMatch || startMatch.index == null) {
    return [];
  }

  const startIndex = startMatch.index + startMatch[0].length;
  const remaining = bodyText.slice(startIndex);
  const endMatch = remaining.match(endPattern);
  const block = endMatch && endMatch.index != null
    ? remaining.slice(0, endMatch.index)
    : remaining;

  const results = [];
  const seen = new Set();

  const rolePattern =
    "(?:Assassin|Fighter|Tank|Mage|Marksman|Support)";

  const entryPattern = new RegExp(
    "#\\\\s*\\\\d+\\\\s+" +
    "([A-Za-z0-9 '&.()-]+?)\\\\s+" +
    "([SABCD])\\\\s+" +
    "(" + rolePattern + "(?:\\\\s+" + rolePattern + ")?)" +
    "[\\\\s\\\\S]*?" +
    "(\\\\d+(?:\\\\.\\\\d+)?)\\\\s*%\\\\s+" +
    "\\\\+?(\\\\d+(?:\\\\.\\\\d+)?)\\\\s*%\\\\s+" +
    "win rate delta",
    "gi"
  );

  let match;

  while ((match = entryPattern.exec(block)) !== null) {
    const name = clean(match[1]);
    const tier = clean(match[2]).toUpperCase();
    const roleText = clean(match[3]);
    const winRate = Number(match[4]);
    const delta = Number(match[5]);

    if (!name) continue;

    const fallbackId = slugFromHeroName(name);
    const asset = findHeroAsset($, name, fallbackId);

    if (
      !asset.id ||
      asset.id === currentId ||
      seen.has(asset.id)
    ) {
      continue;
    }

    const roles = [];
    for (const role of [
      "Assassin",
      "Fighter",
      "Tank",
      "Mage",
      "Marksman",
      "Support"
    ]) {
      if (new RegExp("\\\\b" + role + "\\\\b", "i").test(roleText)) {
        roles.push(role);
      }
    }

    seen.add(asset.id);

    results.push({
      id: asset.id,
      name,
      tier,
      roles,
      role: roles.join(", "),
      win_rate: winRate,
      delta,
      image: asset.image,
      details_url: asset.details_url
    });

    if (results.length >= 5) break;
  }

  return results;
}

function parseCompactCounterBlock($, bodyText, currentId) {
  const sectionMatch = bodyText.match(
    /Counter Picks\s+See all\s+Weak Against\s+(.+?)\s+Strong Against\s+(.+?)\s+Full\s+[A-Za-z0-9 '&.-]+\s+counter guide/i
  );

  if (!sectionMatch) {
    return {
      counters: [],
      strong_against: []
    };
  }

  function parseList(block) {
    const output = [];
    const regex = /([A-Za-z0-9 '&.-]+?)\s+(\d+(?:\.\d+)?)\s*%/g;
    let match;

    while ((match = regex.exec(block)) !== null) {
      const name = clean(match[1]);
      const winRate = Number(match[2]);

      if (!name) continue;

      const fallbackId = slugFromHeroName(name);
      const asset = findHeroAsset($, name, fallbackId);

      if (
        !asset.id ||
        asset.id === currentId ||
        output.some((item) => item.id === asset.id)
      ) {
        continue;
      }

      output.push({
        id: asset.id,
        name,
        tier: "",
        roles: [],
        role: "",
        win_rate: winRate,
        delta: null,
        image: asset.image,
        details_url: asset.details_url
      });

      if (output.length >= 5) break;
    }

    return output;
  }

  return {
    counters: parseList(sectionMatch[1]),
    strong_against: parseList(sectionMatch[2])
  };
}


function parseMatchupsByDocumentOrder($, heroName, currentId) {
  const counters = [];
  const strongAgainst = [];
  const seenCounters = new Set();
  const seenStrong = new Set();

  const counterHeading = new RegExp(
    "^How to Counter\\s+" + escapeRegExp(heroName) + "$",
    "i"
  );

  const strongHeading = new RegExp(
    "^Heroes\\s+" + escapeRegExp(heroName) + "\\s+Counters$",
    "i"
  );

  let mode = "";

  $("h1, h2, h3, h4, a[href*='/heroes/']").each((_, node) => {
    const element = $(node);

    if (element.is("h1, h2, h3, h4")) {
      const headingText = clean(element.text());

      if (counterHeading.test(headingText)) {
        mode = "counter";
      } else if (strongHeading.test(headingText)) {
        mode = "strong";
      } else if (mode && element.is("h2")) {
        mode = "";
      }

      return;
    }

    if (!mode) return;

    const anchor = element;
    const href = absoluteUrl(anchor.attr("href"));

    let parsedUrl;

    try {
      parsedUrl = new URL(href);
    } catch {
      return;
    }

    const parts = parsedUrl.pathname.split("/").filter(Boolean);

    if (parts.length !== 2 || parts[0] !== "heroes") return;

    const id = clean(parts[1]).toLowerCase();

    if (!id || id === currentId) return;

    const seen = mode === "counter" ? seenCounters : seenStrong;

    if (seen.has(id)) return;

    const img = anchor.find("img").first();

    let name = clean(img.attr("alt"))
      .replace(/\s+hero icon$/i, "")
      .replace(/\s+hero$/i, "");

    if (!name) {
      name = clean(anchor.text())
        .replace(/^#\s*\d+\s*/i, "")
        .replace(/\b[SABCD]\b/g, "")
        .replace(/\b(Assassin|Fighter|Tank|Mage|Marksman|Support)\b/gi, "")
        .replace(/\d+(?:\.\d+)?\s*%[\s\S]*$/i, "")
        .trim();
    }

    if (!name) name = titleFromSlug(id);

    let card = anchor;
    let cardText = clean(anchor.text());

    for (let depth = 0; depth < 8; depth++) {
      const parent = card.parent();

      if (!parent.length) break;

      const parentText = clean(parent.text());

      if (
        parentText.length >= cardText.length &&
        parentText.length <= 1200
      ) {
        card = parent;
        cardText = parentText;

        if (/win rate delta/i.test(cardText)) {
          break;
        }
      } else {
        break;
      }
    }

    const tier = firstMatch(cardText, [
      /\b([SABCD])\b/i
    ]).toUpperCase();

    const roles = [];

    for (const role of [
      "Assassin",
      "Fighter",
      "Tank",
      "Mage",
      "Marksman",
      "Support"
    ]) {
      if (new RegExp("\\b" + role + "\\b", "i").test(cardText)) {
        roles.push(role);
      }
    }

    const percentages = Array.from(
      cardText.matchAll(/(\d+(?:\.\d+)?)\s*%/g)
    ).map((match) => Number(match[1]));

    const deltaMatch = cardText.match(
      /(\d+(?:\.\d+)?)\s*%\s+win rate delta/i
    );

    const winRate = percentages.length > 0
      ? percentages[0]
      : null;

    const delta = deltaMatch
      ? Number(deltaMatch[1])
      : percentages.length > 1
        ? percentages[1]
        : null;

    const image = absoluteUrl(
      img.attr("src") ||
      img.attr("data-src") ||
      img.attr("data-lazy-src")
    );

    const item = {
      id,
      name,
      tier,
      roles,
      role: roles.join(", "),
      win_rate: winRate,
      delta,
      image,
      details_url: `${ORIGIN}/heroes/${id}`
    };

    seen.add(id);

    if (mode === "counter" && counters.length < 5) {
      counters.push(item);
    } else if (mode === "strong" && strongAgainst.length < 5) {
      strongAgainst.push(item);
    }
  });

  return {
    counters,
    strong_against: strongAgainst
  };
}

function parseFirstBuildSetup($, bodyText) {
  const firstBuild = firstMatch(bodyText, [
    /Aamon Best Builds, Emblems & Talents\s+(.+?)\s+02\s+Enhanced Basic DPS/i,
    /Best Builds, Emblems & Talents\s+(.+?)\s+02\s+/i,
    /Curated Build\s+(.+?)\s+Counter Picks/i
  ]);

  const source = firstBuild || bodyText;

  const emblem = firstMatch(source, [
    /Emblem Setup\s+((?:Assassin|Mage|Fighter|Tank|Marksman|Support|Common)\s+Emblem)\b/i,
    /Emblem\s+((?:Assassin|Mage|Fighter|Tank|Marksman|Support|Common)\s+Emblem)\b/i
  ]);

  const knownTalents = [
    "Rupture",
    "Master Assassin",
    "Killing Spree",
    "Inspire",
    "Bargain Hunter",
    "Lethal Ignition",
    "Agility",
    "Weapons Master",
    "Quantum Charge",
    "Festival of Blood",
    "Brave Smite",
    "Concussive Blast"
  ];

  const talents = knownTalents.filter((talent) =>
    new RegExp("\\b" + talent.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\b", "i")
      .test(source)
  ).slice(0, 3);

  const spell = firstMatch(source, [
    /Battle Spell\s+([A-Za-z ]+?)\s+Blessing/i,
    /Spell\s+([A-Za-z ]+?)(?:\s+Counter Picks|\s*$)/i
  ]);

  const blessing = firstMatch(source, [
    /Blessing\s+([A-Za-z ]+?)(?:\s+Situational Swaps|\s+02\s+|\s*$)/i
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
    emblem,
    talents,
    spell,
    spell_image: spellImage,
    blessing
  };
}



function parseMatchupCards($, headingPattern, currentId, limit = 5) {
  let heading = null;

  $("h2").each((_, node) => {
    if (heading) return false;

    const text = clean($(node).text());
    if (headingPattern.test(text)) {
      heading = $(node);
      return false;
    }
  });

  if (!heading) return [];

  const sectionNodes = [];
  let current = heading.next();

  while (current.length) {
    if (current.is("h2")) break;
    sectionNodes.push(current);
    current = current.next();
  }

  const results = [];
  const seen = new Set();

  for (const node of sectionNodes) {
    node.find('a[href*="/heroes/"]').each((_, anchorNode) => {
      if (results.length >= limit) return false;

      const anchor = $(anchorNode);
      const href = absoluteUrl(anchor.attr("href"));

      let parsed;
      try {
        parsed = new URL(href);
      } catch {
        return;
      }

      const parts = parsed.pathname.split("/").filter(Boolean);
      if (parts.length !== 2 || parts[0] !== "heroes") return;

      const id = clean(parts[1]).toLowerCase();
      if (!id || id === currentId || seen.has(id)) return;

      const img = anchor.find("img").first();
      let name = clean(anchor.text());

      if (!name) {
        name = clean(img.attr("alt"))
          .replace(/\s+hero icon$/i, "")
          .replace(/\s+hero$/i, "");
      }

      if (!name) name = titleFromSlug(id);

      let card = anchor;
      let cardText = clean(anchor.text());

      for (let depth = 0; depth < 7; depth++) {
        const parent = card.parent();
        if (!parent.length) break;

        const parentText = clean(parent.text());
        if (!parentText || parentText.length > 1200) break;

        card = parent;
        cardText = parentText;

        if (/win rate delta/i.test(cardText) && /%/.test(cardText)) {
          break;
        }
      }

      const tier = firstMatch(cardText, [
        new RegExp("\\b" + escapeRegExp(name) + "\\s+([SABCD])\\b", "i"),
        /\b([SABCD])\b/
      ]).toUpperCase();

      const roles = [];
      for (const role of [
        "Assassin", "Fighter", "Tank", "Mage", "Marksman", "Support"
      ]) {
        if (new RegExp("\\b" + role + "\\b", "i").test(cardText)) {
          roles.push(role);
        }
      }

      const rates = [...cardText.matchAll(/(\d+(?:\.\d+)?)\s*%/g)]
        .map((match) => Number(match[1]));

      const image = absoluteUrl(
        img.attr("src") ||
        img.attr("data-src") ||
        img.attr("data-lazy-src")
      );

      seen.add(id);
      results.push({
        id,
        name,
        tier,
        roles,
        role: roles.join(", "),
        win_rate: rates.length > 0 ? rates[0] : null,
        delta: rates.length > 1 ? rates[1] : null,
        image,
        details_url: href
      });
    });

    if (results.length >= limit) break;
  }

  return results;
}


function normalizeSkillType(value) {
  const text = clean(value).toLowerCase();

  if (/passive/.test(text)) return "Passive";
  if (/skill\s*1|first skill/.test(text)) return "Skill 1";
  if (/skill\s*2|second skill/.test(text)) return "Skill 2";
  if (/skill\s*3|third skill/.test(text)) return "Skill 3";
  if (/ultimate|ult\b/.test(text)) return "Ultimate";
  if (/special/.test(text)) return "Special";

  return "";
}

function getSkillImage($, container, skillName, skillType) {
  let image = "";

  container.find("img").each((_, node) => {
    if (image) return false;

    const img = $(node);
    const alt = clean(img.attr("alt"));
    const title = clean(img.attr("title"));
    const combined = `${alt} ${title}`;

    if (
      (skillName && new RegExp(escapeRegExp(skillName), "i").test(combined)) ||
      (skillType && new RegExp(escapeRegExp(skillType), "i").test(combined))
    ) {
      image = absoluteUrl(
        img.attr("src") ||
        img.attr("data-src") ||
        img.attr("data-lazy-src")
      );
      return false;
    }
  });

  return image;
}

function parseSkillSections($, heroName) {
  const skills = [];
  const seen = new Set();

  const headingCandidates = [];

  $("h2, h3, h4, h5").each((_, node) => {
    const heading = $(node);
    const headingText = clean(heading.text());

    if (
      /passive|skill\s*[123]|first skill|second skill|third skill|ultimate|special skill/i
        .test(headingText)
    ) {
      headingCandidates.push(heading);
    }
  });

  for (const heading of headingCandidates) {
    const headingText = clean(heading.text());
    const type = normalizeSkillType(headingText);

    if (!type) continue;

    let name = headingText
      .replace(new RegExp("^" + escapeRegExp(heroName) + "\\s*", "i"), "")
      .replace(/^(Passive|Skill\s*[123]|First Skill|Second Skill|Third Skill|Ultimate|Special Skill)\s*[:\-–—]?\s*/i, "")
      .replace(/\s*[:\-–—]?\s*(Passive|Skill\s*[123]|First Skill|Second Skill|Third Skill|Ultimate|Special Skill)$/i, "")
      .trim();

    let wrapper = cheerio.load("<div id='skill-root'></div>");
    let root = wrapper("#skill-root");

    let current = heading.next();
    let paragraphCount = 0;

    while (current.length) {
      if (current.is("h2, h3, h4, h5")) break;

      root.append(current.clone());

      if (current.is("p") || current.find("p").length > 0) {
        paragraphCount++;
      }

      if (paragraphCount >= 4) break;

      current = current.next();
    }

    const sectionText = clean(root.text());

    if (!name) {
      const strong = clean(root.find("strong, b").first().text());
      if (strong && strong.length <= 80) {
        name = strong;
      }
    }

    if (!name) {
      const titleLike = clean(root.find("h4, h5").first().text());
      if (titleLike && titleLike.length <= 80) {
        name = titleLike;
      }
    }

    if (!name) {
      name = type;
    }

    let description = "";

    root.find("p").each((_, node) => {
      if (description) return false;

      const text = clean(wrapper(node).text());

      if (
        text.length >= 45 &&
        !/recommended build|counter pick|emblem|battle spell/i.test(text)
      ) {
        description = text;
        return false;
      }
    });

    if (!description && sectionText.length >= 45) {
      description = sectionText;
    }

    const image = getSkillImage(wrapper, root, name, type);
    const key = `${type}|${name}`.toLowerCase();

    if (
      !seen.has(key) &&
      description &&
      description.length >= 30
    ) {
      seen.add(key);

      skills.push({
        type,
        name,
        description,
        image
      });
    }
  }

  // Fallback for pages where skill content is article-style and headings are generic.
  if (skills.length === 0) {
    const articleHeadings = [];

    $("h2, h3").each((_, node) => {
      const text = clean($(node).text());

      if (
        new RegExp(
          escapeRegExp(heroName) + ".*(?:Passive|Skill|Ultimate)|(?:Passive|Skill|Ultimate).*" + escapeRegExp(heroName),
          "i"
        ).test(text)
      ) {
        articleHeadings.push($(node));
      }
    });

    for (const heading of articleHeadings) {
      const headingText = clean(heading.text());
      const type = normalizeSkillType(headingText);

      if (!type) continue;

      let current = heading.next();
      let description = "";

      while (current.length && !current.is("h2, h3")) {
        const text = clean(current.text());

        if (text.length >= 45) {
          description = text;
          break;
        }

        current = current.next();
      }

      if (!description) continue;

      let name = headingText
        .replace(new RegExp(escapeRegExp(heroName), "ig"), "")
        .replace(/passive|skill\s*[123]|ultimate|guide|how to use|explained/ig, "")
        .replace(/[:\-–—]+/g, " ")
        .trim();

      if (!name) name = type;

      const key = `${type}|${name}`.toLowerCase();

      if (!seen.has(key)) {
        seen.add(key);

        skills.push({
          type,
          name,
          description,
          image: ""
        });
      }
    }
  }

  const order = {
    "Passive": 0,
    "Skill 1": 1,
    "Skill 2": 2,
    "Skill 3": 3,
    "Ultimate": 4,
    "Special": 5
  };

  skills.sort((a, b) => {
    return (order[a.type] ?? 99) - (order[b.type] ?? 99);
  });

  return skills.slice(0, 6);
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
      /Lane\s+.+?\s+Specialty\s+(.+?)\s+Difficulty\s+(?:Easy|Medium|Hard)/i,
      /Specialty\s+(.+?)\s+Difficulty/i
    ]).replace(/\s*·\s*/g, " · ");

    const difficulty = firstMatch(bodyText, [
      /Specialty\s+.+?\s+Difficulty\s+(Easy|Medium|Hard)\b/i,
      /Difficulty\s+(Easy|Medium|Hard)\b/i
    ]);

    const tier = firstMatch(bodyText, [
      /Subject Codename\s+.+?[“"][^”"]+[”"]\s+Tier\s+Tier\s+([SABCD])\b/i,
      /Codename\s+.+?[“"][^”"]+[”"]\s+Tier\s+([SABCD])\b/i,
      /\bTier\s+([SABCD])\b/i
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

    const orderedMatchups = parseMatchupsByDocumentOrder(
      $,
      name,
      id
    );

    let counters = orderedMatchups.counters;
    let strongAgainst = orderedMatchups.strong_against;

    if (counters.length === 0 || strongAgainst.length === 0) {
      const compactMatchups = parseCompactCounterBlock(
        $,
        bodyText,
        id
      );

      if (counters.length === 0) {
        counters = compactMatchups.counters;
      }

      if (strongAgainst.length === 0) {
        strongAgainst = compactMatchups.strong_against;
      }
    }

    const build = parseBuild($, bodyText);
    const setup = parseFirstBuildSetup($, bodyText);

    if (!build.emblem) build.emblem = setup.emblem;
    if (!build.talents || build.talents.length === 0) {
      build.talents = setup.talents;
    }
    if (!build.spell) build.spell = setup.spell;
    if (!build.spell_image) build.spell_image = setup.spell_image;
    if (!build.blessing) build.blessing = setup.blessing;
    const overviewStats = parseOverviewStats(bodyText);
    const skills = parseSkillSections($, name);

    res.setHeader("Cache-Control", "public, s-maxage=3600, stale-while-revalidate=86400");
    res.setHeader("Access-Control-Allow-Origin", "*");

    return res.status(200).json({
      success: true,
      source,
      fetched_at: new Date().toISOString(),
      parser_version: "v12",
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
        skills
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
