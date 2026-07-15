import * as cheerio from "cheerio";

const SOURCE_URL = "https://mlbbhub.com/heroes";
const SITE_ORIGIN = "https://mlbbhub.com";

function cleanText(value) {
  return String(value || "")
    .replace(/\s+/g, " ")
    .trim();
}

function titleFromSlug(slug) {
  return String(slug || "")
    .split("-")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function parseHeroText(text, fallbackName) {
  const cleaned = cleanText(text);

  // Expected example:
  // Aamon - Assassin - B -Tier - 51.1 % Win Rate
  const match = cleaned.match(
    /^(.+?)\s*-\s*(.+?)\s*-\s*([A-Z])\s*-?\s*Tier\s*-\s*([\d.]+)\s*%\s*Win Rate$/i
  );

  if (!match) {
    return {
      name: fallbackName,
      roles: [],
      tier: "",
      win_rate: null
    };
  }

  return {
    name: cleanText(match[1]) || fallbackName,
    roles: match[2]
      .split(",")
      .map((role) => cleanText(role))
      .filter(Boolean),
    tier: cleanText(match[3]).toUpperCase(),
    win_rate: Number(match[4])
  };
}

function isHeroPath(pathname) {
  const parts = pathname.split("/").filter(Boolean);

  if (parts.length !== 2) return false;
  if (parts[0].toLowerCase() !== "heroes") return false;

  return /^[a-z0-9-]+$/i.test(parts[1]);
}

async function fetchHtml(url, attempts = 3) {
  let lastError;

  for (let attempt = 1; attempt <= attempts; attempt++) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 20000);

    try {
      const response = await fetch(url, {
        method: "GET",
        headers: {
          "User-Agent":
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36",
          "Accept":
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
          "Accept-Language": "en-US,en;q=0.9",
          "Cache-Control": "no-cache"
        },
        signal: controller.signal
      });

      if (!response.ok) {
        throw new Error(`Source returned HTTP ${response.status}`);
      }

      return await response.text();
    } catch (error) {
      lastError = error;

      if (attempt < attempts) {
        await new Promise((resolve) => setTimeout(resolve, attempt * 700));
      }
    } finally {
      clearTimeout(timeout);
    }
  }

  throw lastError || new Error("Unable to fetch source page.");
}

export default async function handler(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");

    return res.status(405).json({
      success: false,
      message: "Method not allowed. Use GET."
    });
  }

  try {
    const html = await fetchHtml(SOURCE_URL);
    const $ = cheerio.load(html);
    const heroMap = new Map();

    $("a[href]").each((_, node) => {
      const element = $(node);
      const rawHref = cleanText(element.attr("href"));

      if (!rawHref) return;

      let url;

      try {
        url = new URL(rawHref, SITE_ORIGIN);
      } catch {
        return;
      }

      if (url.hostname !== "mlbbhub.com") return;
      if (!isHeroPath(url.pathname)) return;

      const parts = url.pathname.split("/").filter(Boolean);
      const slug = cleanText(parts[1]).toLowerCase();

      if (!slug) return;

      let cardText = cleanText(element.text());

      if (!cardText) {
        cardText = cleanText(
          element.attr("aria-label") ||
          element.attr("title") ||
          element.find("img").attr("alt")
        );
      }

      const fallbackName = titleFromSlug(slug);
      const parsed = parseHeroText(cardText, fallbackName);

      const current = heroMap.get(slug);
      const candidate = {
        id: slug,
        name: parsed.name || fallbackName,
        slug,
        roles: parsed.roles,
        role: parsed.roles.join(", "),
        tier: parsed.tier,
        win_rate: parsed.win_rate,
        details_url: `${SITE_ORIGIN}/heroes/${slug}`
      };

      // Prefer the entry containing parsed roles/tier/win rate.
      if (
        !current ||
        candidate.roles.length > current.roles.length ||
        (candidate.win_rate !== null && current.win_rate === null)
      ) {
        heroMap.set(slug, candidate);
      }
    });

    const heroes = Array.from(heroMap.values())
      .filter((hero) => hero.name)
      .sort((a, b) => a.name.localeCompare(b.name));

    if (heroes.length < 100) {
      throw new Error(
        `Only ${heroes.length} hero entries were found. The source layout may have changed.`
      );
    }

    res.setHeader(
      "Cache-Control",
      "public, s-maxage=3600, stale-while-revalidate=86400"
    );
    res.setHeader("Access-Control-Allow-Origin", "*");

    return res.status(200).json({
      success: true,
      source: SOURCE_URL,
      total: heroes.length,
      fetched_at: new Date().toISOString(),
      heroes
    });
  } catch (error) {
    console.error("Heroes scraper failed:", error);

    return res.status(500).json({
      success: false,
      message: "Failed to retrieve the MLBB hero list.",
      error: error instanceof Error ? error.message : String(error)
    });
  }
}
