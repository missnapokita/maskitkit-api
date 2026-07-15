import * as cheerio from "cheerio";

const SITE_ORIGIN = "https://mlbbhub.com";
const HERO_ID_PATTERN = /^[a-z0-9-]+$/i;

function clean(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function numberFromPercent(value) {
  const match = clean(value).match(/-?\d+(?:\.\d+)?/);
  return match ? Number(match[0]) : null;
}

function absoluteUrl(value) {
  if (!value) return "";

  try {
    return new URL(value, SITE_ORIGIN).toString();
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

function firstTextAfterLabel($, label) {
  const normalizedLabel = label.toLowerCase();
  let result = "";

  $("body *").each((_, node) => {
    if (result) return false;

    const element = $(node);
    const ownText = clean(
      element.clone().children().remove().end().text()
    );

    if (ownText.toLowerCase() !== normalizedLabel) return;

    const next = element.next();
    const nextText = clean(next.text());

    if (nextText) {
      result = nextText;
      return false;
    }

    const parent = element.parent();
    const parentText = clean(parent.text());
    const value = clean(
      parentText.replace(new RegExp(`^${label}\\s*`, "i"), "")
    );

    if (value) {
      result = value;
      return false;
    }
  });

  return result;
}

function findHeading($, pattern) {
  let found = null;

  $("h1, h2, h3, h4").each((_, node) => {
    if (found) return false;

    const text = clean($(node).text());
    if (pattern.test(text)) {
      found = $(node);
      return false;
    }
  });

  return found;
}

function sectionUntilNextHeading($, heading) {
  const nodes = [];

  if (!heading || !heading.length) return nodes;

  let current = heading.next();

  while (current.length) {
    if (current.is("h1, h2, h3")) break;
    nodes.push(current);
    current = current.next();
  }

  return nodes;
}

function parseHeroAnchors($, nodes, currentSlug, limit = 5) {
  const output = [];
  const seen = new Set();

  for (const node of nodes) {
    node.find('a[href^="/heroes/"], a[href*="mlbbhub.com/heroes/"]').each(
      (_, anchorNode) => {
        if (output.length >= limit) return false;

        const anchor = $(anchorNode);
        const href = absoluteUrl(anchor.attr("href"));

        let url;
        try {
          url = new URL(href);
        } catch {
          return;
        }

        const parts = url.pathname.split("/").filter(Boolean);
        if (parts.length !== 2 || parts[0] !== "heroes") return;

        const slug = clean(parts[1]).toLowerCase();
        if (!slug || slug === currentSlug || seen.has(slug)) return;

        const text = clean(anchor.text());
        const img = anchor.find("img").first();

        let name = clean(img.attr("alt"))
          .replace(/\s+hero icon$/i, "")
          .replace(/\s+hero$/i, "");

        if (!name) {
          name = text
            .replace(/\b[SABCD]\b/g, "")
            .replace(/\d+(?:\.\d+)?\s*%.*$/i, "")
            .replace(/\b(Assassin|Fighter|Tank|Mage|Marksman|Support)\b/gi, "")
            .trim();
        }

        if (!name) name = titleFromSlug(slug);

        const percentMatch = text.match(/(\d+(?:\.\d+)?)\s*%/);
        const tierMatch = text.match(/\b([SABCD])\b/);
        const roles = [];

        for (const role of [
          "Assassin",
          "Fighter",
          "Tank",
          "Mage",
          "Marksman",
          "Support"
        ]) {
          if (new RegExp(`\\b${role}\\b`, "i").test(text)) roles.push(role);
        }

        seen.add(slug);
        output.push({
          id: slug,
          name,
          tier: tierMatch ? tierMatch[1].toUpperCase() : "",
          roles,
          role: roles.join(", "),
          win_rate: percentMatch ? Number(percentMatch[1]) : null,
          image: absoluteUrl(
            img.attr("src") ||
            img.attr("data-src") ||
            img.attr("data-lazy-src")
          ),
          details_url: `${SITE_ORIGIN}/heroes/${slug}`
        });
      }
    );
  }

  return output;
}

function parseFirstBuild($) {
  const heading =
    findHeading($, /best builds?.*emblems?/i) ||
    findHeading($, /curated build/i);

  const nodes = sectionUntilNextHeading($, heading);
  const wrapper = $("<div></div>");

  for (const node of nodes) wrapper.append(node.clone());

  let buildName = "";
  wrapper.find("h3, h4").each((_, node) => {
    if (buildName) return false;

    const value = clean($(node).text());
    if (value && !/emblem|spell|swap/i.test(value)) {
      buildName = value.replace(/^\d+\s*/, "");
      return false;
    }
  });

  const itemMap = new Map();

  wrapper.find('a[href*="/items/"], a[href*="/item/"]').each((_, node) => {
    const anchor = $(node);
    const name = clean(anchor.text());
    if (!name || itemMap.has(name.toLowerCase())) return;

    const img = anchor.find("img").first();

    itemMap.set(name.toLowerCase(), {
      name,
      image: absoluteUrl(
        img.attr("src") ||
        img.attr("data-src") ||
        img.attr("data-lazy-src")
      ),
      url: absoluteUrl(anchor.attr("href"))
    });
  });

  // The site may use equipment links that do not include /items/.
  if (itemMap.size < 6) {
    wrapper.find("a[href]").each((_, node) => {
      if (itemMap.size >= 6) return false;

      const anchor = $(node);
      const href = clean(anchor.attr("href"));
      const name = clean(anchor.text());

      if (
        !name ||
        /simulator|emblem|counter|hero|spell|retribution/i.test(name) ||
        !href
      ) return;

      const img = anchor.find("img").first();
      const key = name.toLowerCase();

      if (!itemMap.has(key)) {
        itemMap.set(key, {
          name,
          image: absoluteUrl(
            img.attr("src") ||
            img.attr("data-src") ||
            img.attr("data-lazy-src")
          ),
          url: absoluteUrl(href)
        });
      }
    });
  }

  const fullText = clean(wrapper.text());

  const emblemMatch = fullText.match(
    /(?:Emblem Setup|Emblem)\s+(.+? Emblem)(?:\s|$)/i
  );

  const spellMatch = fullText.match(
    /Battle Spell\s+([A-Za-z ]+?)(?:\s+Blessing|\s+Situational|\s*$)/i
  );

  const blessingMatch = fullText.match(
    /Blessing\s+([A-Za-z ]+?)(?:\s+Situational|\s*$)/i
  );

  const costMatch = fullText.match(/Cost\s+([\d,]+)\s*g/i);

  return {
    name: buildName || "Recommended Build",
    cost: costMatch ? costMatch[1].replace(/,/g, "") : "",
    items: Array.from(itemMap.values()).slice(0, 6),
    emblem: emblemMatch ? clean(emblemMatch[1]) : "",
    spell: spellMatch ? clean(spellMatch[1]) : "",
    blessing: blessingMatch ? clean(blessingMatch[1]) : ""
  };
}

async function fetchHtml(url, attempts = 3) {
  let lastError;

  for (let attempt = 1; attempt <= attempts; attempt++) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 20000);

    try {
      const response = await fetch(url, {
        headers: {
          "User-Agent":
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36",
          "Accept":
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
          "Accept-Language": "en-US,en;q=0.9"
        },
        signal: controller.signal
      });

      if (!response.ok) {
        throw new Error(`MLBBHub returned HTTP ${response.status}`);
      }

      return await response.text();
    } catch (error) {
      lastError = error;

      if (attempt < attempts) {
        await new Promise((resolve) =>
          setTimeout(resolve, attempt * 700)
        );
      }
    } finally {
      clearTimeout(timeout);
    }
  }

  throw lastError || new Error("Unable to fetch hero page.");
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

  if (!id || !HERO_ID_PATTERN.test(id)) {
    return res.status(400).json({
      success: false,
      message: "A valid hero id is required.",
      example: "/api/hero?id=aamon"
    });
  }

  try {
    const sourceUrl = `${SITE_ORIGIN}/heroes/${id}`;
    const html = await fetchHtml(sourceUrl);
    const $ = cheerio.load(html);

    const pageTitle = clean($("h1").first().text());
    const codename =
      firstTextAfterLabel($, "Codename") ||
      firstTextAfterLabel($, "Subject Codename") ||
      titleFromSlug(id);

    const heroTitle =
      clean(
        $('meta[property="og:title"]').attr("content")
      ).match(/-\s*([^,]+),\s*Mobile Legends/i)?.[1] ||
      clean($("body").text()).match(/[“"]([^”"]+)[”"]/)?.[1] ||
      "";

    const role =
      firstTextAfterLabel($, "Combat Class") ||
      firstTextAfterLabel($, "Role");

    const laneRaw = firstTextAfterLabel($, "Lane");
    const lane = clean(
      laneRaw
        .replace(/\blane\b/gi, "")
        .replace(/\bJungler\b/gi, "Jungle")
    );

    const specialty = firstTextAfterLabel($, "Specialty");
    const difficulty = firstTextAfterLabel($, "Difficulty");

    const bodyText = clean($("body").text());

    const tier =
      bodyText.match(/\bTier\s+([SABCD])\b/i)?.[1]?.toUpperCase() || "";

    const winRate =
      numberFromPercent(
        bodyText.match(/Win Rate\s+(\d+(?:\.\d+)?)\s*%/i)?.[1]
      );

    const pickRate =
      numberFromPercent(
        bodyText.match(/Pick Rate\s+(\d+(?:\.\d+)?)\s*%/i)?.[1]
      );

    const banRate =
      numberFromPercent(
        bodyText.match(/Ban Rate\s+(\d+(?:\.\d+)?)\s*%/i)?.[1]
      );

    let overview = "";

    $("p").each((_, node) => {
      if (overview) return false;

      const text = clean($(node).text());

      if (
        text.length >= 100 &&
        new RegExp(`\\b${codename}\\b`, "i").test(text) &&
        !/cookie|privacy|advertisement|fan-made|not affiliated/i.test(text)
      ) {
        overview = text;
        return false;
      }
    });

    const ogImage = absoluteUrl(
      $('meta[property="og:image"]').attr("content")
    );

    let heroImage = "";

    $("img").each((_, node) => {
      if (heroImage) return false;

      const image = $(node);
      const alt = clean(image.attr("alt"));

      if (
        new RegExp(`\\b${codename}\\b`, "i").test(alt) &&
        /mobile legends|hero/i.test(alt)
      ) {
        heroImage = absoluteUrl(
          image.attr("src") ||
          image.attr("data-src") ||
          image.attr("data-lazy-src")
        );
        return false;
      }
    });

    const counterHeading = findHeading(
      $,
      new RegExp(`How to Counter\\s+${codename}`, "i")
    );

    const strongHeading = findHeading(
      $,
      new RegExp(`Heroes\\s+${codename}\\s+Counters`, "i")
    );

    const counters = parseHeroAnchors(
      $,
      sectionUntilNextHeading($, counterHeading),
      id,
      5
    );

    const strongAgainst = parseHeroAnchors(
      $,
      sectionUntilNextHeading($, strongHeading),
      id,
      5
    );

    const build = parseFirstBuild($);

    res.setHeader(
      "Cache-Control",
      "public, s-maxage=3600, stale-while-revalidate=86400"
    );
    res.setHeader("Access-Control-Allow-Origin", "*");

    return res.status(200).json({
      success: true,
      source: sourceUrl,
      fetched_at: new Date().toISOString(),
      hero: {
        id,
        name: codename,
        title: heroTitle,
        page_title: pageTitle,
        role,
        lane,
        specialty,
        difficulty,
        tier,
        win_rate: winRate,
        pick_rate: pickRate,
        ban_rate: banRate,
        overview,
        image: heroImage || ogImage,
        backdrop: ogImage,
        build,
        counters,
        strong_against: strongAgainst,

        // Skill data appears to be loaded dynamically on parts of the site.
        // We will add a dedicated parser after this core endpoint is verified.
        skills: []
      }
    });
  } catch (error) {
    console.error("Single hero scraper failed:", error);

    return res.status(500).json({
      success: false,
      message: "Failed to retrieve the hero guide.",
      error: error instanceof Error ? error.message : String(error)
    });
  }
}
