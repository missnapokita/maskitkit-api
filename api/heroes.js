import * as cheerio from "cheerio";

const SOURCE_URL = "https://mlbbhub.com/heroes";
const SITE_ORIGIN = "https://mlbbhub.com";

function cleanText(value) {
  return String(value || "")
    .replace(/\s+/g, " ")
    .trim();
}

function formatHeroName(slug) {
  return String(slug || "")
    .split("-")
    .filter(Boolean)
    .map((word) => {
      return word.charAt(0).toUpperCase() + word.slice(1);
    })
    .join(" ");
}

function isValidHeroSlug(slug) {
  if (!slug) return false;

  const blocked = new Set([
    "tier-list",
    "win-rates",
    "counters",
    "builds",
    "items",
    "emblems",
    "skills",
    "guides"
  ]);

  if (blocked.has(slug)) return false;

  return /^[a-z0-9-]+$/i.test(slug);
}

export default async function handler(req, res) {
  if (req.method !== "GET") {
    return res.status(405).json({
      success: false,
      message: "Method not allowed. Use GET."
    });
  }

  try {
    const response = await fetch(SOURCE_URL, {
      method: "GET",
      headers: {
        "User-Agent":
          "Mozilla/5.0 (compatible; MasterKitDataBot/1.0)",
        "Accept":
          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9"
      },
      signal: AbortSignal.timeout(20000)
    });

    if (!response.ok) {
      throw new Error(
        `MLBBHub returned HTTP ${response.status}`
      );
    }

    const html = await response.text();
    const $ = cheerio.load(html);

    const heroMap = new Map();

    $('a[href^="/heroes/"], a[href*="mlbbhub.com/heroes/"]').each(
      function () {
        const element = $(this);
        const rawHref = cleanText(element.attr("href"));

        if (!rawHref) return;

        let parsedUrl;

        try {
          parsedUrl = new URL(rawHref, SITE_ORIGIN);
        } catch (error) {
          return;
        }

        if (parsedUrl.hostname !== "mlbbhub.com") return;

        const pathParts = parsedUrl.pathname
          .split("/")
          .filter(Boolean);

        if (pathParts.length !== 2) return;
        if (pathParts[0] !== "heroes") return;

        const slug = cleanText(pathParts[1]).toLowerCase();

        if (!isValidHeroSlug(slug)) return;

        let name = cleanText(
          element.find("h2, h3, h4, [class*='name']").first().text()
        );

        if (!name) {
          name = cleanText(
            element.attr("aria-label") ||
            element.attr("title") ||
            element.find("img").attr("alt") ||
            element.text()
          );
        }

        name = name
          .replace(/\bbuild\b.*$/i, "")
          .replace(/\bguide\b.*$/i, "")
          .replace(/\bcounter\b.*$/i, "")
          .replace(/\s+/g, " ")
          .trim();

        if (!name || name.length > 40) {
          name = formatHeroName(slug);
        }

        heroMap.set(slug, {
          id: slug,
          name,
          slug,
          details_url: `${SITE_ORIGIN}/heroes/${slug}`
        });
      }
    );

    const heroes = Array.from(heroMap.values()).sort((a, b) =>
      a.name.localeCompare(b.name)
    );

    if (heroes.length === 0) {
      throw new Error(
        "No hero links were found. The website structure may have changed."
      );
    }

    res.setHeader(
      "Cache-Control",
      "s-maxage=3600, stale-while-revalidate=86400"
    );

    return res.status(200).json({
      success: true,
      source: SOURCE_URL,
      total: heroes.length,
      fetched_at: new Date().toISOString(),
      heroes
    });
  } catch (error) {
    console.error("Hero scraper error:", error);

    return res.status(500).json({
      success: false,
      message: "Failed to fetch the hero list.",
      error:
        error instanceof Error
          ? error.message
          : String(error)
    });
  }
}
