import * as cheerio from "cheerio";

const ORIGIN = "https://mlbbhub.com";

function clean(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
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
  } finally {
    clearTimeout(timeout);
  }
}

function sliceAround(text, needle, before = 150, after = 1600) {
  const index = text.toLowerCase().indexOf(needle.toLowerCase());

  if (index < 0) return "";

  return text.slice(
    Math.max(0, index - before),
    Math.min(text.length, index + after)
  );
}

export default async function handler(req, res) {
  const rawId = Array.isArray(req.query.id) ? req.query.id[0] : req.query.id;
  const id = clean(rawId || "aamon").toLowerCase();
  const source = `${ORIGIN}/heroes/${id}`;

  try {
    const html = await fetchHtml(source);
    const $ = cheerio.load(html);
    const bodyText = clean($("body").text());

    const headings = [];
    $("h1, h2, h3, h4").each((_, node) => {
      const text = clean($(node).text());
      if (text) headings.push(text);
    });

    const heroLinks = [];
    $("a[href*='/heroes/']").each((_, node) => {
      const anchor = $(node);
      const text = clean(anchor.text());
      const href = clean(anchor.attr("href"));

      if (
        /hayabusa|gusion|silvanna|atlas|gloo|marcel|masha|hilda|claude|cici/i
          .test(text)
      ) {
        heroLinks.push({
          text,
          href,
          parent_text: clean(anchor.parent().text()),
          grandparent_text: clean(anchor.parent().parent().text()).slice(0, 500)
        });
      }
    });

    res.setHeader("Cache-Control", "no-store");
    res.setHeader("Access-Control-Allow-Origin", "*");

    return res.status(200).json({
      success: true,
      source,
      debug_version: "counter-debug-v1",
      has_how_to_counter: bodyText.includes(`How to Counter ${id.charAt(0).toUpperCase() + id.slice(1)}`),
      has_counter_picks: bodyText.includes("Counter Picks"),
      headings,
      how_to_counter_text: sliceAround(bodyText, "How to Counter"),
      counter_picks_text: sliceAround(bodyText, "Counter Picks"),
      matched_hero_links: heroLinks
    });
  } catch (error) {
    return res.status(500).json({
      success: false,
      message: "Counter debug failed.",
      error: error instanceof Error ? error.message : String(error)
    });
  }
}
