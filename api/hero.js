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

function percentAfterLabel(bodyText, label) {
  const pattern = new RegExp(
    label + "\\s+(\\d+(?:\\.\\d+)?)\\s*%",
    "i"
  );
  const match = bodyText.match(pattern);
  return match ? Number(match[1]) : null;
}

function textAfterExactLabel($, label) {
  let result = "";

  $("body *").each((_, node) => {
    if (result) return false;

    const element = $(node);
    const own = clean(
      element.clone().children().remove().end().text()
    );

    if (own.toLowerCase() !== label.toLowerCase()) return;

    let next = element.next();

    while (next.length) {
      const text = clean(next.text());

      if (text) {
        result = text;
        return false;
      }

      next = next.next();
    }
  });

  return result;
}

function findHeading($, regex) {
  let result = null;

  $("h1, h2, h3, h4").each((_, node) => {
    if (result) return false;

    const text = clean($(node).text());

    if (regex.test(text)) {
      result = $(node);
      return false;
    }
  });

  return result;
}

function collectUntilNextHeading(heading, levels = "h2") {
  const wrapper = cheerio.load("<div id='root'></div>");
  const root = wrapper("#root");

  if (!heading || !heading.length) return root;

  let current = heading.next();

  while (current.length) {
    if (current.is(levels)) break;
    root.append(current.clone());
    current = current.next();
  }

  return root;
}

function parseHeroLinks($, root, currentId, limit) {
  const list = [];
  const seen = new Set();

  root.find('a[href*="/heroes/"]').each((_, node) => {
    if (list.length >= limit) return false;

    const anchor = $(node);
    const href = absoluteUrl(anchor.attr("href"));

    let url;
    try {
      url = new URL(href);
    } catch {
      return;
    }

    const parts = url.pathname.split("/").filter(Boolean);

    if (parts.length !== 2 || parts[0] !== "heroes") return;

    const id = clean(parts[1]).toLowerCase();

    if (!id || id === currentId || seen.has(id)) return;

    const raw = clean(anchor.text());
    const img = anchor.find("img").first();

    let name = clean(img.attr("alt"))
      .replace(/\s+hero icon$/i, "")
      .replace(/\s+hero$/i, "");

    if (!name) {
      name = raw
        .replace(/\d+(?:\.\d+)?\s*%.*$/i, "")
        .replace(/\b[SABCD]\b/g, "")
        .replace(
          /\b(Assassin|Fighter|Tank|Mage|Marksman|Support)\b/gi,
          ""
        )
        .trim();
    }

    if (!name) name = titleFromSlug(id);

    const tierMatch = raw.match(/\b([SABCD])\b/);
    const rateMatch = raw.match(/(\d+(?:\.\d+)?)\s*%/);

    const roles = [];
    for (const role of [
      "Assassin",
      "Fighter",
      "Tank",
      "Mage",
      "Marksman",
      "Support"
    ]) {
      if (new RegExp("\\b" + role + "\\b", "i").test(raw)) {
        roles.push(role);
      }
    }

    seen.add(id);

    list.push({
      id,
      name,
      tier: tierMatch ? tierMatch[1].toUpperCase() : "",
      roles,
      role: roles.join(", "),
      win_rate: rateMatch ? Number(rateMatch[1]) : null,
      image: absoluteUrl(
        img.attr("src") ||
        img.attr("data-src") ||
        img.attr("data-lazy-src")
      ),
      details_url: `${ORIGIN}/heroes/${id}`
    });
  });

  return list;
}

function parseBuild($) {
  const heading = findHeading(
    $,
    /Best Builds?, Emblems? & Talents|Curated Build/i
  );

  if (!heading) {
    return {
      name: "",
      description: "",
      cost: "",
      items: [],
      emblem: "",
      talents: [],
      spell: "",
      blessing: ""
    };
  }

  const root = collectUntilNextHeading(heading, "h2");

  const name = clean(root.find("h3").first().text());

  let description = "";
  const firstParagraph = root.find("p").first();
  if (firstParagraph.length) {
    description = clean(firstParagraph.text());
  }

  const text = clean(root.text());

  const costMatch = text.match(/Cost\s+([\d,]+)\s*g/i);
  const cost = costMatch ? costMatch[1].replace(/,/g, "") : "";

  const items = [];
  const seen = new Set();

  root.find("a[href]").each((_, node) => {
    if (items.length >= 6) return false;

    const anchor = $(node);
    const href = absoluteUrl(anchor.attr("href"));
    const label = clean(anchor.text());

    if (!href || !label) return;

    if (
      /simulator|emblem setup|emblem|counter|hero|see all/i.test(label)
    ) {
      return;
    }

    const url = new URL(href);

    if (
      !/\/items?\//i.test(url.pathname) &&
      !/arcane-boots|genius-wand|holy-crystal|divine-glaive|blood-wings|winter-crown|feather-of-heaven|starlium-scythe|sky-piercer|glowing-wand|tough-boots|immortality/i.test(
        url.pathname
      )
    ) {
      return;
    }

    const key = label.toLowerCase();

    if (seen.has(key)) return;

    const img = anchor.find("img").first();

    seen.add(key);
    items.push({
      name: label,
      image: absoluteUrl(
        img.attr("src") ||
        img.attr("data-src") ||
        img.attr("data-lazy-src")
      ),
      url: href
    });
  });

  let emblem = "";
  let talents = [];

  root.find("a").each((_, node) => {
    const label = clean($(node).text());

    if (!/^Emblem Setup\b/i.test(label)) return;

    const stripped = label.replace(/^Emblem Setup\s*/i, "");
    const emblemMatch = stripped.match(
      /^(Assassin|Mage|Fighter|Tank|Marksman|Support|Common)\s+Emblem\b/i
    );

    if (emblemMatch) {
      emblem = clean(emblemMatch[0]);
      const rest = clean(stripped.slice(emblemMatch[0].length));
      talents = rest
        ? rest.split(/\s{2,}|,\s*/)
        : [];
    }
  });

  if (talents.length === 0 && emblem) {
    const stripped = text.match(
      /Emblem Setup\s+(?:Assassin|Mage|Fighter|Tank|Marksman|Support|Common)\s+Emblem\s+(.+?)\s+Battle Spell/i
    );

    if (stripped) {
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

      talents = knownTalents.filter((talent) =>
        stripped[1].toLowerCase().includes(talent.toLowerCase())
      );
    }
  }

  const spellMatch = text.match(
    /Battle Spell\s+([A-Za-z ]+?)(?=\s+Blessing|\s+Situational|\s*$)/i
  );

  const blessingMatch = text.match(
    /Blessing\s+([A-Za-z ]+?)(?=\s+Situational|\s*$)/i
  );

  let spellImage = "";

  root.find("img").each((_, node) => {
    if (spellImage) return false;

    const image = $(node);
    const alt = clean(image.attr("alt"));

    if (/Retribution|Flicker|Execute|Inspire|Purify|Sprint|Aegis|Revitalize|Vengeance|Flameshot|Arrival/i.test(alt)) {
      spellImage = absoluteUrl(
        image.attr("src") ||
        image.attr("data-src") ||
        image.attr("data-lazy-src")
      );
      return false;
    }
  });

  return {
    name: name || "Recommended Build",
    description,
    cost,
    items,
    emblem,
    talents,
    spell: spellMatch ? clean(spellMatch[1]) : "",
    spell_image: spellImage,
    blessing: blessingMatch ? clean(blessingMatch[1]) : ""
  };
}

async function fetchHtml(url) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 20000);

  try {
    const response = await fetch(url, {
      headers: {
        "User-Agent":
          "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
          "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36",
        Accept:
          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9"
      },
      signal: controller.signal
    });

    if (!response.ok) {
      throw new Error(`MLBBHub returned HTTP ${response.status}`);
    }

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

  const rawId = Array.isArray(req.query.id)
    ? req.query.id[0]
    : req.query.id;

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

    const name =
      textAfterExactLabel($, "Codename") ||
      textAfterExactLabel($, "Subject Codename") ||
      titleFromSlug(id);

    const titleMatch = bodyText.match(
      new RegExp(name + '\\s+[“"]([^”"]+)[”"]', "i")
    );

    const role =
      textAfterExactLabel($, "Combat Class") ||
      textAfterExactLabel($, "Role");

    const laneRaw = textAfterExactLabel($, "Lane");

    let lane = clean(laneRaw)
      .replace(/\blane\b/gi, "")
      .replace(/\bJungler\b/gi, "Jungle")
      .replace(/\s+/g, " ")
      .trim();

    if (/^Jungle\s+Jungle$/i.test(lane)) lane = "Jungle";

    const specialty = textAfterExactLabel($, "Specialty")
      .replace(/\s*·\s*/g, " · ");

    const difficulty = textAfterExactLabel($, "Difficulty");

    const tierMatch = bodyText.match(/\bTier\s+([SABCD])\b/i);

    let overview = "";

    $("p").each((_, node) => {
      if (overview) return false;

      const text = clean($(node).text());

      if (
        text.length >= 100 &&
        new RegExp("\\b" + name + "\\b", "i").test(text) &&
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
        new RegExp("\\b" + name + "\\b", "i").test(alt) &&
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

    const backdrop = absoluteUrl(
      $('meta[property="og:image"]').attr("content")
    );

    const counterHeading = findHeading(
      $,
      new RegExp("How to Counter\\s+" + name, "i")
    );

    const strongHeading = findHeading(
      $,
      new RegExp("Heroes\\s+" + name + "\\s+Counters", "i")
    );

    const counters = counterHeading
      ? parseHeroLinks(
          $,
          collectUntilNextHeading(counterHeading, "h2"),
          id,
          5
        )
      : [];

    const strongAgainst = strongHeading
      ? parseHeroLinks(
          $,
          collectUntilNextHeading(strongHeading, "h2"),
          id,
          5
        )
      : [];

    const build = parseBuild($);

    res.setHeader(
      "Cache-Control",
      "public, s-maxage=3600, stale-while-revalidate=86400"
    );
    res.setHeader("Access-Control-Allow-Origin", "*");

    return res.status(200).json({
      success: true,
      source,
      fetched_at: new Date().toISOString(),
      hero: {
        id,
        name,
        title: titleMatch ? clean(titleMatch[1]) : "",
        role: clean(role),
        lane,
        specialty: clean(specialty),
        difficulty: clean(difficulty),
        tier: tierMatch ? tierMatch[1].toUpperCase() : "",
        win_rate: percentAfterLabel(bodyText, "Win Rate"),
        pick_rate: percentAfterLabel(bodyText, "Pick Rate"),
        ban_rate: percentAfterLabel(bodyText, "Ban Rate"),
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
