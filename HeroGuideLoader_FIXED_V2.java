package com.iandev.masterkit;

import android.app.Activity;
import android.os.AsyncTask;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.util.ArrayList;

/**
 * MasterKit Hero Guide loader.
 *
 * Main guide data:
 *   https://maskitkit-api.vercel.app/api/hero?id={heroId}
 *
 * Secondary image/details source:
 *   https://raw.githubusercontent.com/p3hndrx/MLBB-API/refs/heads/main/v1/hero-meta-final.json
 *
 * The current API remains the source for build, counters, strong-against,
 * win/pick/ban rate, overview, and other MasterKit fields.
 *
 * MLBB-API is only used to improve:
 *   - hero banner portrait
 *   - hero role/lane fallback
 *   - skills and skill icons fallback
 */
public class HeroGuideLoader {

	private static final String API_URL =
			"https://maskitkit-api.vercel.app/api/hero?id=";

	private static final String MLBB_HERO_DATA_URL =
			"https://raw.githubusercontent.com/p3hndrx/MLBB-API/refs/heads/main/v1/hero-meta-final.json";

	private static final String MLBB_RAW_BASE_URL =
			"https://raw.githubusercontent.com/p3hndrx/MLBB-API/refs/heads/main/";

	private static JSONObject cachedMlbbRoot;

	private HeroGuideLoader() {
	}

	public static void apply(final Activity activity) {

		if (activity == null || activity.isFinishing()) {
			return;
		}

		final String heroId =
				safe(activity.getIntent().getStringExtra("hero_id"));

		final String heroName =
				safe(activity.getIntent().getStringExtra("hero_name"));

		setupToolbar(activity, heroName);

		if (heroId.isEmpty()) {
			showError(
					activity,
					"Missing hero ID. Please open this page from the hero list."
			);
			return;
		}

		setupRetry(activity, heroId);
		load(activity, heroId);
	}

	private static void setupToolbar(
			final Activity activity,
			String heroName) {

		TextView toolbarTitle =
				activity.findViewById(R.id.textview_toolbar_title);

		if (toolbarTitle != null) {
			toolbarTitle.setText(
					heroName.isEmpty()
							? "Hero Guide"
							: heroName + " Guide"
			);
		}

		ImageView back =
				activity.findViewById(R.id.imageview_back);

		if (back != null) {
			back.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View view) {
					activity.finish();
				}
			});
		}
	}

	private static void setupRetry(
			final Activity activity,
			final String heroId) {

		TextView retry =
				activity.findViewById(R.id.textview_retry);

		if (retry == null) {
			return;
		}

		retry.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				load(activity, heroId);
			}
		});
	}

	private static void load(
			final Activity activity,
			final String heroId) {

		showLoading(activity);

		new AsyncTask<Void, Void, Result>() {

			@Override
			protected Result doInBackground(Void... voids) {

				try {
					JSONObject hero =
							loadMasterKitHero(heroId);

					if (hero == null) {
						return Result.error(
								"Hero data was not found."
						);
					}

					/*
					 * MLBB-API is optional.
					 * The Hero Guide still works when this second request fails.
					 */
					try {
						JSONObject mlbbHero =
								findMlbbHero(hero);

						if (mlbbHero != null) {
							mergeMlbbImagesAndSkills(
									hero,
									mlbbHero
							);
						}
					} catch (Exception ignored) {
						// Keep current MasterKit data as fallback.
					}

					return Result.success(hero);

				} catch (Exception e) {

					return Result.error(
							e.getMessage() == null
									? "Connection failed."
									: e.getMessage()
					);
				}
			}

			@Override
			protected void onPostExecute(Result result) {

				if (activity.isFinishing()) {
					return;
				}

				if (!result.success ||
						result.hero == null) {

					showError(
							activity,
							result.message
					);

					return;
				}

				bindHero(
						activity,
						result.hero
				);
			}

		}.execute();
	}

	private static JSONObject loadMasterKitHero(
			String heroId) throws Exception {

		String encodedId =
				URLEncoder.encode(heroId, "UTF-8");

		String response =
				request(API_URL + encodedId);

		JSONObject root =
				new JSONObject(response);

		if (!root.optBoolean("success", false)) {
			throw new Exception(
					valueOr(
							root.optString("message"),
							"Unable to load hero guide."
					)
			);
		}

		return root.optJSONObject("hero");
	}

	private static JSONObject getMlbbRoot()
			throws Exception {

		if (cachedMlbbRoot != null) {
			return cachedMlbbRoot;
		}

		String response =
				request(MLBB_HERO_DATA_URL);

		cachedMlbbRoot =
				new JSONObject(response);

		return cachedMlbbRoot;
	}

	private static JSONObject findMlbbHero(
			JSONObject masterHero) throws Exception {

		JSONObject root =
				getMlbbRoot();

		JSONArray data =
				root.optJSONArray("data");

		if (data == null) {
			return null;
		}

		String currentId =
				safe(masterHero.optString("id"));

		String currentName =
				safe(masterHero.optString("name"));

		String normalizedName =
				normalize(currentName);

		/*
		 * Prefer name matching because some APIs use a slug for "id",
		 * while MLBB-API uses the numeric "mlid".
		 */
		for (int i = 0; i < data.length(); i++) {

			JSONObject item =
					data.optJSONObject(i);

			if (item == null) {
				continue;
			}

			String itemName =
					safe(item.optString("hero_name"));

			if (!normalizedName.isEmpty() &&
					normalize(itemName).equals(normalizedName)) {

				return item;
			}
		}

		/*
		 * Numeric ID fallback.
		 */
		if (isNumeric(currentId)) {

			for (int i = 0; i < data.length(); i++) {

				JSONObject item =
						data.optJSONObject(i);

				if (item == null) {
					continue;
				}

				if (currentId.equals(
						safe(item.optString("mlid"))
				)) {
					return item;
				}
			}
		}

		return null;
	}

	private static void mergeMlbbImagesAndSkills(
			JSONObject target,
			JSONObject source) throws Exception {

		String portrait =
				safe(source.optString("portrait"));

		/*
		 * Force the better MLBB portrait into both fields.
		 * Existing UI code can use either "image" or "backdrop".
		 */
		if (isHttpUrl(portrait)) {
			target.put("image", portrait);
			target.put("backdrop", portrait);
			target.put("mlbb_portrait", portrait);
		}

		String sourceRole =
				safe(source.optString("class"));

		if (safe(target.optString("role")).isEmpty() &&
				!sourceRole.isEmpty()) {

			target.put("role", sourceRole);
		}

		if (safe(target.optString("lane")).isEmpty()) {

			JSONArray lanes =
					source.optJSONArray("laning");

			if (lanes != null &&
					lanes.length() > 0) {

				target.put(
						"lane",
						joinArray(lanes)
				);
			}
		}

		JSONArray sourceSkills =
				source.optJSONArray("skills");

		if (sourceSkills == null ||
				sourceSkills.length() == 0) {

			return;
		}

		JSONArray targetSkills =
				target.optJSONArray("skills");

		/*
		 * If the current API has no skills, use the MLBB skills.
		 */
		if (targetSkills == null ||
				targetSkills.length() == 0) {

			target.put(
					"skills",
					convertSkills(sourceSkills)
			);

			return;
		}

		/*
		 * If current skills already exist, only improve their image and
		 * missing details. Matching is first done by skill name and then
		 * by array position.
		 */
		for (int i = 0; i < targetSkills.length(); i++) {

			JSONObject currentSkill =
					targetSkills.optJSONObject(i);

			if (currentSkill == null) {
				continue;
			}

			JSONObject sourceSkill =
					findSourceSkill(
							sourceSkills,
							currentSkill,
							i
					);

			if (sourceSkill == null) {
				continue;
			}

			String skillImage =
					resolveRepoImage(
							sourceSkill.optString("skill_icon")
					);

			if (!skillImage.isEmpty()) {
				currentSkill.put("image", skillImage);
				currentSkill.put("icon", skillImage);
				currentSkill.put("skill_icon", skillImage);
			}

			putIfEmpty(
					currentSkill,
					"name",
					sourceSkill.optString("skill_name")
			);

			putIfEmpty(
					currentSkill,
					"description",
					cleanDescription(
							sourceSkill.optString("description")
					)
			);

			putIfEmpty(
					currentSkill,
					"cooldown",
					sourceSkill.optString("cooldown")
			);

			putIfEmpty(
					currentSkill,
					"mana_cost",
					sourceSkill.optString("manacost")
			);

			putIfEmpty(
					currentSkill,
					"type",
					sourceSkill.optString("type")
			);
		}
	}

	private static JSONArray convertSkills(
			JSONArray sourceSkills) throws Exception {

		JSONArray result =
				new JSONArray();

		for (int i = 0; i < sourceSkills.length(); i++) {

			JSONObject source =
					sourceSkills.optJSONObject(i);

			if (source == null) {
				continue;
			}

			JSONObject skill =
					new JSONObject();

			String name =
					safe(source.optString("skill_name"));

			String image =
					resolveRepoImage(
							source.optString("skill_icon")
					);

			skill.put("name", name);
			skill.put("skill_name", name);
			skill.put("image", image);
			skill.put("icon", image);
			skill.put("skill_icon", image);

			skill.put(
					"description",
					cleanDescription(
							source.optString("description")
					)
			);

			skill.put(
					"cooldown",
					nullTextToEmpty(
							source.optString("cooldown")
					)
			);

			skill.put(
					"mana_cost",
					nullTextToEmpty(
							source.optString("manacost")
					)
			);

			skill.put(
					"type",
					nullTextToEmpty(
							source.optString("type")
					)
			);

			result.put(skill);
		}

		return result;
	}

	private static JSONObject findSourceSkill(
			JSONArray sourceSkills,
			JSONObject currentSkill,
			int position) {

		String currentName =
				normalize(
						valueOr(
								currentSkill.optString("name"),
								currentSkill.optString("skill_name")
						)
				);

		if (!currentName.isEmpty()) {

			for (int i = 0; i < sourceSkills.length(); i++) {

				JSONObject source =
						sourceSkills.optJSONObject(i);

				if (source == null) {
					continue;
				}

				if (currentName.equals(
						normalize(
								source.optString("skill_name")
						)
				)) {
					return source;
				}
			}
		}

		return sourceSkills.optJSONObject(position);
	}

	private static String resolveRepoImage(
			String path) {

		String clean =
				safe(path);

		if (clean.isEmpty() ||
				"null".equalsIgnoreCase(clean)) {

			return "";
		}

		if (isHttpUrl(clean)) {
			return clean;
		}

		while (clean.startsWith("/")) {
			clean = clean.substring(1);
		}

		return MLBB_RAW_BASE_URL + clean;
	}

	private static void putIfEmpty(
			JSONObject object,
			String key,
			String value) throws Exception {

		if (safe(object.optString(key)).isEmpty() &&
				!safe(value).isEmpty() &&
				!"null".equalsIgnoreCase(safe(value))) {

			object.put(key, value);
		}
	}

	private static String cleanDescription(
			String value) {

		String clean =
				nullTextToEmpty(value);

		if (clean.isEmpty()) {
			return "";
		}

		return clean
				.replace("\\n", "\n")
				.replaceAll("[\\t ]+", " ")
				.replaceAll("\\n{3,}", "\n\n")
				.trim();
	}

	private static String nullTextToEmpty(
			String value) {

		String clean =
				safe(value);

		return "null".equalsIgnoreCase(clean)
				? ""
				: clean;
	}

	private static String joinArray(
			JSONArray array) {

		StringBuilder result =
				new StringBuilder();

		for (int i = 0; i < array.length(); i++) {

			String value =
					nullTextToEmpty(array.optString(i));

			if (value.isEmpty()) {
				continue;
			}

			if (result.length() > 0) {
				result.append(" / ");
			}

			result.append(toTitleCase(value));
		}

		return result.toString();
	}

	private static String request(
			String targetUrl) throws Exception {

		HttpURLConnection connection = null;

		try {
			URL url =
					new URL(targetUrl);

			connection =
					(HttpURLConnection) url.openConnection();

			connection.setRequestMethod("GET");
			connection.setConnectTimeout(15000);
			connection.setReadTimeout(30000);
			connection.setUseCaches(true);

			connection.setRequestProperty(
					"Accept",
					"application/json"
			);

			connection.setRequestProperty(
					"User-Agent",
					"MasterKit-Android"
			);

			int responseCode =
					connection.getResponseCode();

			InputStream inputStream =
					responseCode >= 200 &&
					responseCode < 300
							? connection.getInputStream()
							: connection.getErrorStream();

			String response =
					readStream(inputStream);

			if (responseCode < 200 ||
					responseCode >= 300) {

				throw new Exception(
						extractError(response)
				);
			}

			return response;

		} finally {

			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	private static void bindHero(
			Activity activity,
			JSONObject hero) {

		String id =
				safe(hero.optString("id"));

		String name =
				safe(hero.optString("name"));

		String title =
				safe(hero.optString("title"));

		String role =
				safe(hero.optString("role"));

		String lane =
				safe(hero.optString("lane"));

		String specialty =
				safe(hero.optString("specialty"));

		String difficulty =
				safe(hero.optString("difficulty"));

		String tier =
				safe(hero.optString("tier"));

		String overview =
				safe(hero.optString("overview"));

		String image =
				safe(hero.optString("image"));

		String backdrop =
				safe(hero.optString("backdrop"));

		setText(
				activity,
				R.id.textview_hero_name,
				valueOr(name, formatHeroName(id))
		);

		setText(
				activity,
				R.id.textview_toolbar_title,
				valueOr(name, "Hero") + " Guide"
		);

		setText(
				activity,
				R.id.textview_hero_title,
				valueOr(title, "Mobile Legends Hero")
		);

		setText(
				activity,
				R.id.textview_role,
				valueOr(role, "Unknown Role")
		);

		setText(
				activity,
				R.id.textview_lane,
				valueOr(lane, "Unknown Lane")
		);

		String specialtyText =
				specialty.isEmpty()
						? difficulty
						: specialty;

		setText(
				activity,
				R.id.textview_specialty,
				valueOr(specialtyText, "Hero")
		);

		setText(
				activity,
				R.id.textview_tier,
				valueOr(tier, "-")
		);

		setText(
				activity,
				R.id.textview_overview,
				valueOr(
						overview,
						"No hero overview is available."
				)
		);

		setPercentage(
				activity,
				R.id.textview_win_rate,
				hero,
				"win_rate"
		);

		setPercentage(
				activity,
				R.id.textview_pick_rate,
				hero,
				"pick_rate"
		);

		setPercentage(
				activity,
				R.id.textview_ban_rate,
				hero,
				"ban_rate"
		);

		/*
		 * "backdrop" is now the MLBB portrait after merging.
		 */
		String heroImage =
				!backdrop.isEmpty()
						? backdrop
						: image;

		loadImage(
				activity,
				R.id.imageview_hero_background,
				heroImage
		);

		bindBuild(
				activity,
				hero.optJSONObject("build")
		);

		bindSkills(
				activity,
				hero.optJSONArray("skills"),
				R.id.linear_skills_section
		);

		bindHeroCards(
				activity,
				hero.optJSONArray("counters"),
				R.id.linear_counters_section
		);

		bindHeroCards(
				activity,
				hero.optJSONArray("strong_against"),
				R.id.linear_strong_against_section
		);

		bindLaningCombos(
				activity,
				hero.optJSONArray("laning_combos")
		);

		showContent(activity);
	}

	private static void bindBuild(
			Activity activity,
			JSONObject build) {

		View buildSection =
				activity.findViewById(
						R.id.linear_build_section
				);

		View setupSection =
				activity.findViewById(
						R.id.linear_setup_section
				);

		if (build == null) {

			setVisibility(buildSection, View.GONE);
			setVisibility(setupSection, View.GONE);
			return;
		}

		JSONArray items =
				build.optJSONArray("items");

		String emblem =
				safe(build.optString("emblem"));

		/*
		 * Some API records have an empty emblem field but provide the
		 * recommended emblem talents. Show those instead of "Not available".
		 */
		if (emblem.isEmpty()) {
			emblem = joinArray(
					build.optJSONArray("talents")
			);
		}

		String spell =
				safe(build.optString("spell"));

		String buildName =
				safe(build.optString("name"));

		boolean hasItems =
				items != null &&
				items.length() > 0;

		boolean hasSetup =
				!emblem.isEmpty() ||
				!spell.isEmpty();

		setVisibility(
				buildSection,
				hasItems ? View.VISIBLE : View.GONE
		);

		setVisibility(
				setupSection,
				hasSetup ? View.VISIBLE : View.GONE
		);

		if (!buildName.isEmpty()) {
			setText(
					activity,
					R.id.textview_build_type,
					buildName
			);
		}

		setText(
				activity,
				R.id.textview_emblem,
				valueOr(emblem, "Not available")
		);

		setText(
				activity,
				R.id.textview_spell,
				valueOr(spell, "Not available")
		);

		TextView emblemView =
				activity.findViewById(R.id.textview_emblem);

		if (emblemView != null) {
			emblemView.setSingleLine(false);
			emblemView.setMaxLines(3);
		}

		TextView spellView =
				activity.findViewById(R.id.textview_spell);

		if (spellView != null) {
			spellView.setSingleLine(false);
			spellView.setMaxLines(2);
		}

		if (!hasItems) {
			return;
		}

		ArrayList<HeroBuildItem> buildList =
				new ArrayList<>();

		for (int i = 0; i < items.length(); i++) {

			JSONObject itemObject =
					items.optJSONObject(i);

			if (itemObject == null) {
				continue;
			}

			HeroBuildItem item =
					new HeroBuildItem();

			item.name =
					safe(itemObject.optString("name"));

			item.image =
					safe(itemObject.optString("image"));

			buildList.add(item);
		}

		RecyclerView recyclerView =
				activity.findViewById(
						R.id.recyclerview_build
				);

		if (recyclerView != null) {

			recyclerView.setLayoutManager(
					new LinearLayoutManager(
							activity,
							LinearLayoutManager.HORIZONTAL,
							false
					)
			);

			recyclerView.setAdapter(
					new HeroBuildAdapter(
							activity,
							buildList
					)
			);
		}
	}

	private static void bindLaningCombos(
			Activity activity,
			JSONArray combos) {

		View sectionView =
				activity.findViewById(
						R.id.linear_skills_section
				);

		if (sectionView == null) {
			return;
		}

		if (combos == null ||
				combos.length() == 0) {

			sectionView.setVisibility(View.GONE);
			return;
		}

		sectionView.setVisibility(View.VISIBLE);
		makeWrapContent(sectionView);

		/*
		 * Do not append another large card below the existing placeholder.
		 * Replace the placeholder description already present in the XML.
		 */
		StringBuilder content =
				new StringBuilder();

		for (int i = 0; i < combos.length(); i++) {

			JSONObject combo =
					combos.optJSONObject(i);

			if (combo == null) {
				continue;
			}

			String title =
					valueOr(
							combo.optString("title"),
							"Combo " + (i + 1)
					);

			String description =
					valueOr(
							combo.optString("description"),
							combo.optString("combo")
					);

			if (description.isEmpty()) {
				description =
						valueOr(
								combo.optString("sequence"),
								""
						);
			}

			if (content.length() > 0) {
				content.append("\n\n");
			}

			content.append(title);

			if (!description.isEmpty()) {
				content.append("\n");
				content.append(description);
			}
		}

		TextView placeholder =
				findTextViewContaining(
						sectionView,
						"recommended combo sequence"
				);

		if (placeholder == null) {
			placeholder =
					findFirstDescriptionTextView(
							sectionView
					);
		}

		if (placeholder != null) {
			placeholder.setText(
					valueOr(
							content.toString(),
							"No combo information available."
					)
			);
			placeholder.setMaxLines(Integer.MAX_VALUE);
			placeholder.setSingleLine(false);
		}

		/*
		 * Remove cards generated by the previous patch so they will not
		 * appear below the original XML card.
		 */
		if (sectionView instanceof LinearLayout) {
			removeGeneratedViews(
					(LinearLayout) sectionView
			);
		}
	}

	private static void bindSkills(
			Activity activity,
			JSONArray skills,
			int sectionId) {

		View sectionView =
				activity.findViewById(sectionId);

		if (!(sectionView instanceof LinearLayout)) {
			setVisibility(
					sectionView,
					skills != null && skills.length() > 0
							? View.VISIBLE
							: View.GONE
			);
			return;
		}

		LinearLayout section =
				(LinearLayout) sectionView;

		removeGeneratedViews(section);

		if (skills == null ||
				skills.length() == 0) {

			section.setVisibility(View.GONE);
			return;
		}

		section.setVisibility(View.VISIBLE);

		for (int i = 0; i < skills.length(); i++) {

			JSONObject skill =
					skills.optJSONObject(i);

			if (skill == null) {
				continue;
			}

			String name =
					valueOr(
							skill.optString("name"),
							skill.optString("skill_name")
					);

			String description =
					valueOr(
							skill.optString("description"),
							"No description available."
					);

			String cooldown =
					safe(skill.optString("cooldown"));

			String mana =
					safe(skill.optString("mana_cost"));

			StringBuilder extra =
					new StringBuilder();

			if (!cooldown.isEmpty()) {
				extra.append("Cooldown: ").append(cooldown);
			}

			if (!mana.isEmpty()) {
				if (extra.length() > 0) {
					extra.append("   •   ");
				}
				extra.append("Mana: ").append(mana);
			}

			if (extra.length() > 0) {
				description =
						extra.toString() +
						"\n\n" +
						description;
			}

			LinearLayout card =
					createTextCard(
							activity,
							valueOr(name, "Skill " + (i + 1)),
							description
					);

			card.setTag("hero_guide_generated");
			section.addView(card);
		}
	}

	private static void bindHeroCards(
			Activity activity,
			JSONArray heroes,
			int sectionId) {

		View sectionView =
				activity.findViewById(sectionId);

		if (!(sectionView instanceof LinearLayout)) {
			setVisibility(
					sectionView,
					heroes != null && heroes.length() > 0
							? View.VISIBLE
							: View.GONE
			);
			return;
		}

		LinearLayout section =
				(LinearLayout) sectionView;

		removeGeneratedViews(section);
		hideRecyclerViews(section);
		makeWrapContent(section);

		if (heroes == null ||
				heroes.length() == 0) {

			section.setVisibility(View.GONE);
			return;
		}

		section.setVisibility(View.VISIBLE);

		HorizontalScrollView scroll =
				new HorizontalScrollView(activity);

		scroll.setHorizontalScrollBarEnabled(false);
		scroll.setFillViewport(false);
		scroll.setTag("hero_guide_generated");

		LinearLayout row =
				new LinearLayout(activity);

		row.setOrientation(LinearLayout.HORIZONTAL);

		int side =
				dp(activity, 4);

		row.setPadding(side, dp(activity, 8), side, dp(activity, 8));

		for (int i = 0; i < heroes.length(); i++) {

			JSONObject hero =
					heroes.optJSONObject(i);

			if (hero == null) {
				continue;
			}

			String name =
					valueOr(
							hero.optString("name"),
							"Hero"
					);

			String detail =
					cleanSmallDetail(
							hero.optString("roles")
					);

			if (detail.isEmpty()) {
				detail =
						cleanSmallDetail(
								hero.optString("tier")
						);
			}

			LinearLayout card =
					createHeroCard(
							activity,
							name,
							detail,
							valueOr(
									hero.optString("image"),
									hero.optString("portrait")
							)
					);

			row.addView(card);
		}

		scroll.addView(row);
		section.addView(scroll);
	}

	private static LinearLayout createHeroCard(
			Activity activity,
			String name,
			String detail,
			String imageUrl) {

		LinearLayout card =
				new LinearLayout(activity);

		card.setOrientation(LinearLayout.VERTICAL);
		card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
		card.setPadding(
				dp(activity, 10),
				dp(activity, 10),
				dp(activity, 10),
				dp(activity, 10)
		);

		LinearLayout.LayoutParams cardParams =
				new LinearLayout.LayoutParams(
						dp(activity, 116),
						LinearLayout.LayoutParams.WRAP_CONTENT
				);

		cardParams.setMargins(
				dp(activity, 4),
				0,
				dp(activity, 8),
				0
		);

		card.setLayoutParams(cardParams);

		ImageView image =
				new ImageView(activity);

		image.setScaleType(ImageView.ScaleType.CENTER_CROP);

		LinearLayout.LayoutParams imageParams =
				new LinearLayout.LayoutParams(
						dp(activity, 82),
						dp(activity, 82)
				);

		image.setLayoutParams(imageParams);
		image.setImageResource(R.drawable.des);
		card.addView(image);

		if (!safe(imageUrl).isEmpty()) {
			try {
				SimpleImageLoader.load(
						imageUrl,
						image,
						R.drawable.des,
						null
				);
			} catch (Exception ignored) {
			}
		}

		TextView nameView =
				new TextView(activity);

		nameView.setText(name);
		nameView.setTextColor(0xFFFFFFFF);
		nameView.setTextSize(14);
		nameView.setGravity(android.view.Gravity.CENTER);
		nameView.setMaxLines(2);
		nameView.setPadding(0, dp(activity, 7), 0, 0);
		card.addView(nameView);

		if (!safe(detail).isEmpty()) {

			TextView detailView =
					new TextView(activity);

			detailView.setText(detail);
			detailView.setTextColor(0xFF8E8E93);
			detailView.setTextSize(11);
			detailView.setGravity(android.view.Gravity.CENTER);
			detailView.setMaxLines(2);
			card.addView(detailView);
		}

		return card;
	}

	private static LinearLayout createTextCard(
			Activity activity,
			String title,
			String description) {

		LinearLayout card =
				new LinearLayout(activity);

		card.setOrientation(LinearLayout.VERTICAL);
		card.setPadding(
				dp(activity, 16),
				dp(activity, 14),
				dp(activity, 16),
				dp(activity, 14)
		);

		LinearLayout.LayoutParams params =
				new LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT,
						LinearLayout.LayoutParams.WRAP_CONTENT
				);

		params.setMargins(
				0,
				dp(activity, 8),
				0,
				dp(activity, 4)
		);

		card.setLayoutParams(params);

		TextView titleView =
				new TextView(activity);

		titleView.setText(title);
		titleView.setTextColor(0xFFFFFFFF);
		titleView.setTextSize(16);
		titleView.setTypeface(
				titleView.getTypeface(),
				android.graphics.Typeface.BOLD
		);

		card.addView(titleView);

		TextView descriptionView =
				new TextView(activity);

		descriptionView.setText(description);
		descriptionView.setTextColor(0xFFB3B3B8);
		descriptionView.setTextSize(14);
		descriptionView.setLineSpacing(
				0,
				1.15f
		);
		descriptionView.setPadding(
				0,
				dp(activity, 7),
				0,
				0
		);

		card.addView(descriptionView);

		return card;
	}

	private static void hideRecyclerViews(
			View view) {

		if (view == null) {
			return;
		}

		if (view instanceof RecyclerView) {
			view.setVisibility(View.GONE);
			return;
		}

		if (view instanceof android.view.ViewGroup) {

			android.view.ViewGroup group =
					(android.view.ViewGroup) view;

			for (int i = 0;
				i < group.getChildCount();
				i++) {

				hideRecyclerViews(
						group.getChildAt(i)
				);
			}
		}
	}

	private static void makeWrapContent(
			View view) {

		if (view == null) {
			return;
		}

		view.setMinimumHeight(0);

		android.view.ViewGroup.LayoutParams params =
				view.getLayoutParams();

		if (params != null) {
			params.height =
					android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
			view.setLayoutParams(params);
		}
	}

	private static TextView findTextViewContaining(
			View view,
			String expectedText) {

		if (view == null) {
			return null;
		}

		if (view instanceof TextView) {

			TextView textView =
					(TextView) view;

			String current =
					safe(
							String.valueOf(
									textView.getText()
							)
					).toLowerCase();

			if (current.contains(
					safe(expectedText).toLowerCase()
			)) {
				return textView;
			}
		}

		if (view instanceof android.view.ViewGroup) {

			android.view.ViewGroup group =
					(android.view.ViewGroup) view;

			for (int i = 0;
				i < group.getChildCount();
				i++) {

				TextView result =
						findTextViewContaining(
								group.getChildAt(i),
								expectedText
						);

				if (result != null) {
					return result;
				}
			}
		}

		return null;
	}

	private static TextView findFirstDescriptionTextView(
			View view) {

		if (view == null) {
			return null;
		}

		if (view instanceof TextView) {

			TextView textView =
					(TextView) view;

			String current =
					safe(
							String.valueOf(
									textView.getText()
							)
					);

			/*
			 * Skip short headings such as "Laning Combo".
			 */
			if (current.length() > 20) {
				return textView;
			}
		}

		if (view instanceof android.view.ViewGroup) {

			android.view.ViewGroup group =
					(android.view.ViewGroup) view;

			for (int i = 0;
				i < group.getChildCount();
				i++) {

				TextView result =
						findFirstDescriptionTextView(
								group.getChildAt(i)
						);

				if (result != null) {
					return result;
				}
			}
		}

		return null;
	}

	private static String cleanSmallDetail(
			String value) {

		String clean =
				nullTextToEmpty(value);

		if (clean.equals("[]") ||
				clean.equals("{}") ||
				clean.equals("[null]") ||
				clean.equalsIgnoreCase("null")) {

			return "";
		}

		if (clean.startsWith("[") &&
				clean.endsWith("]")) {

			clean = clean
					.substring(1, clean.length() - 1)
					.replace("\"", "")
					.trim();
		}

		return clean;
	}

	private static void removeGeneratedViews(
			LinearLayout parent) {

		for (int i = parent.getChildCount() - 1;
				i >= 0;
				i--) {

			View child =
					parent.getChildAt(i);

			Object tag =
					child.getTag();

			if ("hero_guide_generated".equals(tag)) {
				parent.removeViewAt(i);
			}
		}
	}

	private static int dp(
			Activity activity,
			int value) {

		return (int) (
				value *
				activity.getResources()
						.getDisplayMetrics()
						.density +
				0.5f
		);
	}

	private static void setPercentage(
			Activity activity,
			int textViewId,
			JSONObject object,
			String key) {

		TextView textView =
				activity.findViewById(textViewId);

		if (textView == null) {
			return;
		}

		if (object.isNull(key)) {
			textView.setText("--");
			return;
		}

		double value =
				object.optDouble(key, Double.NaN);

		if (Double.isNaN(value)) {
			textView.setText("--");
			return;
		}

		String formatted =
				value == Math.floor(value)
						? String.valueOf((int) value)
						: String.valueOf(value);

		textView.setText(formatted + "%");
	}

	private static void loadImage(
			Activity activity,
			int imageViewId,
			String imageUrl) {

		ImageView imageView =
				activity.findViewById(imageViewId);

		if (imageView == null ||
				safe(imageUrl).isEmpty()) {

			return;
		}

		try {
			SimpleImageLoader.load(
					imageUrl,
					imageView,
					R.drawable.des,
					null
			);

		} catch (Exception e) {

			imageView.setImageResource(
					R.drawable.des
			);
		}
	}

	private static void showLoading(
			Activity activity) {

		setVisible(activity, R.id.linear_loading, true);
		setVisible(activity, R.id.linear_error, false);
		setVisible(activity, R.id.vscroll_hero, false);
	}

	private static void showContent(
			Activity activity) {

		setVisible(activity, R.id.linear_loading, false);
		setVisible(activity, R.id.linear_error, false);
		setVisible(activity, R.id.vscroll_hero, true);
	}

	private static void showError(
			Activity activity,
			String message) {

		setText(
				activity,
				R.id.textview_error_message,
				valueOr(
						message,
						"Unable to load hero guide."
				)
		);

		setVisible(activity, R.id.linear_loading, false);
		setVisible(activity, R.id.vscroll_hero, false);
		setVisible(activity, R.id.linear_error, true);
	}

	private static void setVisible(
			Activity activity,
			int viewId,
			boolean visible) {

		View view =
				activity.findViewById(viewId);

		if (view != null) {
			view.setVisibility(
					visible
							? View.VISIBLE
							: View.GONE
			);
		}
	}

	private static void setVisibility(
			View view,
			int visibility) {

		if (view != null) {
			view.setVisibility(visibility);
		}
	}

	private static void setText(
			Activity activity,
			int viewId,
			String text) {

		TextView textView =
				activity.findViewById(viewId);

		if (textView != null) {
			textView.setText(
					valueOr(text, "")
			);
		}
	}

	private static String readStream(
			InputStream inputStream)
			throws Exception {

		if (inputStream == null) {
			return "";
		}

		BufferedReader reader =
				new BufferedReader(
						new InputStreamReader(
								inputStream,
								"UTF-8"
						)
				);

		StringBuilder result =
				new StringBuilder();

		String line;

		while ((line = reader.readLine()) != null) {
			result.append(line);
		}

		reader.close();

		return result.toString();
	}

	private static String extractError(
			String response) {

		try {
			JSONObject object =
					new JSONObject(response);

			String message =
					safe(object.optString("message"));

			String error =
					safe(object.optString("error"));

			if (!message.isEmpty() &&
					!error.isEmpty()) {

				return message + "\n" + error;
			}

			if (!message.isEmpty()) {
				return message;
			}

			if (!error.isEmpty()) {
				return error;
			}

		} catch (Exception ignored) {
		}

		return "Server request failed.";
	}

	private static String normalize(
			String value) {

		return safe(value)
				.toLowerCase()
				.replace("&", "and")
				.replace("’", "")
				.replace("'", "")
				.replace(".", "")
				.replace("-", "")
				.replace("_", "")
				.replace(" ", "");
	}

	private static boolean isNumeric(
			String value) {

		if (safe(value).isEmpty()) {
			return false;
		}

		for (int i = 0; i < value.length(); i++) {
			if (!Character.isDigit(value.charAt(i))) {
				return false;
			}
		}

		return true;
	}

	private static boolean isHttpUrl(
			String value) {

		String clean =
				safe(value).toLowerCase();

		return clean.startsWith("https://") ||
				clean.startsWith("http://");
	}

	private static String toTitleCase(
			String value) {

		String clean =
				safe(value).replace(",", " / ");

		String[] parts =
				clean.split(" ");

		StringBuilder result =
				new StringBuilder();

		for (String part : parts) {

			if (part.isEmpty()) {
				continue;
			}

			if (result.length() > 0) {
				result.append(" ");
			}

			result.append(
					Character.toUpperCase(
							part.charAt(0)
					)
			);

			if (part.length() > 1) {
				result.append(
						part.substring(1).toLowerCase()
				);
			}
		}

		return result.toString();
	}

	private static String formatHeroName(
			String id) {

		if (safe(id).isEmpty()) {
			return "Hero";
		}

		String[] parts =
				id.trim().split("-");

		StringBuilder result =
				new StringBuilder();

		for (String part : parts) {

			if (part.isEmpty()) {
				continue;
			}

			if (result.length() > 0) {
				result.append(" ");
			}

			result.append(
					Character.toUpperCase(
							part.charAt(0)
					)
			);

			if (part.length() > 1) {
				result.append(
						part.substring(1)
				);
			}
		}

		return result.toString();
	}

	private static String safe(
			String value) {

		return value == null
				? ""
				: value.trim();
	}

	private static String valueOr(
			String value,
			String fallback) {

		String cleanValue =
				safe(value);

		return cleanValue.isEmpty()
				? fallback
				: cleanValue;
	}

	private static class Result {

		final boolean success;
		final JSONObject hero;
		final String message;

		private Result(
				boolean success,
				JSONObject hero,
				String message) {

			this.success = success;
			this.hero = hero;
			this.message = message;
		}

		static Result success(
				JSONObject hero) {

			return new Result(
					true,
					hero,
					""
			);
		}

		static Result error(
				String message) {

			return new Result(
					false,
					null,
					message
			);
		}
	}
}
