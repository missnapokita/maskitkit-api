import * as cheerio from "cheerio";

const ORIGIN = "https://mlbbhub.com";

function clean(value) {
  return String(value || "")
    .replace(/\s+/g, " ")
    .trim();
}

async function fetchHtml(url) {
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

function absoluteUrl(value) {
  if (!value) return "";

  try {
    return new URL(value, ORIGIN).toString();
  } catch {
    return "";
  }
}

function sliceAround(text, needle, before = 180, after = 1400) {
  const index = text.toLowerCase().indexOf(
    String(needle || "").toLowerCase()
  );

  if (index < 0) return "";

  return text.slice(
    Math.max(0, index - before),
    Math.min(text.length, index + after)
  );
}

export default async function handler(req, res) {
  const rawId = Array.isArray(req.query.id)
    ? req.query.id[0]
    : req.query.id;

  const id = clean(rawId || "masha").toLowerCase();
  const source = `${ORIGIN}/heroes/${id}`;

  try {
    const html = await fetchHtml(source);
    const $ = cheerio.load(html);
    const bodyText = clean($("body").text());

    const headings = [];

    $("h1, h2, h3, h4, h5, h6").each((_, node) => {
      const text = clean($(node).text());

      if (text) {
        headings.push({
          tag: String(node.tagName || "").toLowerCase(),
          text
        });
      }
    });

    const skillImages = [];

    $("img").each((_, node) => {
      const image = $(node);

      const alt = clean(image.attr("alt"));
      const title = clean(image.attr("title"));
      const src = absoluteUrl(
        image.attr("src") ||
        image.attr("data-src") ||
        image.attr("data-lazy-src")
      );

      const combined = `${alt} ${title} ${src}`;

      if (
        /passive|skill|ultimate|ancient strength|wild power|howl shock|thunderclap|life recovery/i
          .test(combined)
      ) {
        skillImages.push({
          alt,
          title,
          src
        });
      }
    });

    const skillLinks = [];

    $("a[href]").each((_, node) => {
      const anchor = $(node);
      const text = clean(anchor.text());
      const href = absoluteUrl(anchor.attr("href"));

      if (
        /passive|skill|ultimate|ancient strength|wild power|howl shock|thunderclap|life recovery/i
          .test(`${text} ${href}`)
      ) {
        skillLinks.push({
          text,
          href,
          parent_text: clean(anchor.parent().text()).slice(0, 900),
          grandparent_text: clean(
            anchor.parent().parent().text()
          ).slice(0, 1400)
        });
      }
    });

    const skillContainers = [];

    $("section, article, div, li").each((_, node) => {
      const element = $(node);
      const text = clean(element.text());

      if (
        text.length >= 20 &&
        text.length <= 2500 &&
        /ancient strength|wild power|howl shock|thunderclap|life recovery|passive skill|ultimate skill/i
          .test(text)
      ) {
        const images = [];

        element.find("img").each((__, imageNode) => {
          const image = $(imageNode);

          images.push({
            alt: clean(image.attr("alt")),
            src: absoluteUrl(
              image.attr("src") ||
              image.attr("data-src") ||
              image.attr("data-lazy-src")
            )
          });
        });

        skillContainers.push({
          tag: String(node.tagName || "").toLowerCase(),
          class: clean(element.attr("class")),
          id: clean(element.attr("id")),
          text: text.slice(0, 2200),
          images
        });
      }
    });

    const knownTerms = [
      "Ancient Strength",
      "Wild Power",
      "Howl Shock",
      "Thunderclap",
      "Life Recovery",
      "Passive",
      "Ultimate",
      "Skills"
    ];

    const textSlices = {};

    for (const term of knownTerms) {
      const slice = sliceAround(bodyText, term);

      if (slice) {
        textSlices[term] = slice;
      }
    }

    res.setHeader("Cache-Control", "no-store");
    res.setHeader("Access-Control-Allow-Origin", "*");

    return res.status(200).json({
      success: true,
      source,
      debug_version: "skills-debug-v1",
      hero_id: id,
      has_skills_word: /\bskills?\b/i.test(bodyText),
      has_passive_word: /\bpassive\b/i.test(bodyText),
      has_ultimate_word: /\bultimate\b/i.test(bodyText),
      headings,
      skill_images: skillImages,
      skill_links: skillLinks,
      skill_containers: skillContainers.slice(0, 30),
      text_slices: textSlices
    });
  } catch (error) {
    return res.status(500).json({
      success: false,
      message: "Skills debug failed.",
      error:
        error instanceof Error
          ? error.message
          : String(error)
    });
  }
}
