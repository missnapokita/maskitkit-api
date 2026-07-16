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
					 * Use one consistent MLBB Wiki icon source for the main hero.
					 * Existing MasterKit image/backdrop stays as fallback.
					 */
					try {
						String wikiIcon =
								WikiHeroImageResolver.getIconBlocking(
										hero.optString("name")
								);

						if (!wikiIcon.isEmpty()) {
							/*
							 * Wiki icon is only for portrait/icon use.
							 * Never overwrite the main landscape backdrop.
							 */
							hero.put("image", wikiIcon);
							hero.put("wiki_icon", wikiIcon);
						}

						String heroName =
								hero.optString("name");

						if (safe(hero.optString("role")).isEmpty()) {
							String wikiRole =
									WikiHeroImageResolver.getRoleBlocking(
											heroName
									);

							if (!wikiRole.isEmpty()) {
								hero.put("role", wikiRole);
							}
						}

						if (safe(hero.optString("lane")).isEmpty()) {
							String wikiLane =
									WikiHeroImageResolver.getLaneBlocking(
											heroName
									);

							if (!wikiLane.isEmpty()) {
								hero.put("lane", wikiLane);
							}
						}

						if (safe(hero.optString("specialty")).isEmpty()) {
							String wikiSpecialty =
									WikiHeroImageResolver.getSpecialtyBlocking(
											heroName
									);

							if (!wikiSpecialty.isEmpty()) {
								hero.put("specialty", wikiSpecialty);
							}
						}
					} catch (Exception ignored) {
						// Keep current MasterKit image as fallback.
					}

					/*
					 * Optional landscape banner from local assets mapping.
					 * It only replaces the current backdrop when the resolved
					 * Wiki/Fandom file is a real landscape image.
					 */
					try {
						String landscapeBanner =
								LocalHeroBannerResolver.getBannerBlocking(
										activity,
										hero.optString("name")
								);

						if (!landscapeBanner.isEmpty()) {
							hero.put(
									"backdrop",
									landscapeBanner
							);

							hero.put(
									"landscape_banner",
									landscapeBanner
							);
						}
					} catch (Exception ignored) {
						// Keep the current MasterKit backdrop.
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

		String id = safe(hero.optString("id"));
		String name = safe(hero.optString("name"));
		String title = safe(hero.optString("title"));
		String role = safe(hero.optString("role"));
		String lane = safe(hero.optString("lane"));
		String specialty = safe(hero.optString("specialty"));
		String difficulty = safe(hero.optString("difficulty"));
		String tier = safe(hero.optString("tier"));
		String overview = safe(hero.optString("overview"));
		String image = safe(hero.optString("image"));
		String backdrop = safe(hero.optString("backdrop"));

		setText(activity, R.id.textview_hero_name,
				valueOr(name, formatHeroName(id)));

		setText(activity, R.id.textview_toolbar_title,
				valueOr(name, "Hero") + " Guide");

		setText(activity, R.id.textview_hero_title,
				valueOr(title, "Mobile Legends Hero"));

		setText(activity, R.id.textview_role,
				valueOr(role, "Unknown Role"));

		setText(activity, R.id.textview_lane,
				valueOr(lane, "Unknown Lane"));

		String specialtyText =
				specialty.isEmpty() ? difficulty : specialty;

		setText(activity, R.id.textview_specialty,
				valueOr(specialtyText, "Hero"));

		setText(activity, R.id.textview_tier,
				valueOr(tier, "-"));

		setText(activity, R.id.textview_overview,
				valueOr(overview, "No hero overview is available."));

		bindRateCircle(
				activity,
				R.id.circle_win_rate,
				hero.optDouble("win_rate", 0),
				"WIN RATE",
				0xFF2ECC71
		);

		bindRateCircle(
				activity,
				R.id.circle_pick_rate,
				hero.optDouble("pick_rate", 0),
				"PICK RATE",
				0xFFFFB300
		);

		bindRateCircle(
				activity,
				R.id.circle_ban_rate,
				hero.optDouble("ban_rate", 0),
				"BAN RATE",
				0xFFE74C3C
		);

		String heroImage =
				!backdrop.isEmpty() ? backdrop : image;

		loadImage(
				activity,
				R.id.imageview_hero_background,
				heroImage
		);

		bindBuild(
				activity,
				hero.optJSONObject("build")
		);

		bindLaningCombos(
				activity,
				hero.optJSONArray("laning_combos")
		);

		bindCounterList(
				activity,
				hero.optJSONArray("counters"),
				R.id.linear_counters_section,
				R.id.recyclerview_counters
		);

		bindCounterList(
				activity,
				hero.optJSONArray("strong_against"),
				R.id.linear_strong_against_section,
				R.id.recyclerview_strong_against
		);

		showContent(activity);
	}

	private static void bindRateCircle(
			Activity activity,
			int viewId,
			double value,
			String label,
			int color) {

		HeroRateCircleView view =
				activity.findViewById(viewId);

		if (view != null) {
			view.setData(value, label, color, true);
		}
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

		if (emblem.isEmpty()) {
			emblem = firstArrayValue(
					build.optJSONArray("talents")
			);
		}

		String spell =
				safe(build.optString("spell"));

		String buildName =
				safe(build.optString("name"));

		boolean hasItems =
				items != null && items.length() > 0;

		boolean hasSetup =
				!emblem.isEmpty() || !spell.isEmpty();

		setVisibility(
				buildSection,
				hasItems ? View.VISIBLE : View.GONE
		);

		setVisibility(
				setupSection,
				hasSetup ? View.VISIBLE : View.GONE
		);

		setText(
				activity,
				R.id.textview_build_type,
				valueOr(buildName, "Recommended Build")
		);

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

		String emblemImage =
				safe(build.optString("emblem_image"));

		if (emblemImage.isEmpty()) {
			emblemImage =
					LocalEmblemImageResolver.getImage(
							activity,
							emblem
					);
		}

		ImageView emblemView =
				activity.findViewById(
						R.id.imageview_emblem
				);

		if (emblemView != null) {

			if (emblemImage.isEmpty()) {

				emblemView.setImageResource(
						R.drawable.default_image
				);

			} else {

				loadImage(
						activity,
						R.id.imageview_emblem,
						emblemImage
				);
			}
		}

		String spellImage =
				safe(build.optString("spell_image"));

		ImageView spellView =
				activity.findViewById(
						R.id.imageview_spell
				);

		if (spellView != null) {
			if (spellImage.isEmpty()) {
				spellView.setImageResource(
						R.drawable.default_image
				);
			} else {
				loadImage(
						activity,
						R.id.imageview_spell,
						spellImage
				);
			}
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

			if (item.image.isEmpty()) {
				item.image =
						LocalItemImageResolver.getImage(
								activity,
								item.name
						);
			}

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

			recyclerView.setNestedScrollingEnabled(false);
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

		View section =
				activity.findViewById(
						R.id.linear_skills_section
				);

		TextView description =
				activity.findViewById(
						R.id.textview_combo_description
				);

		RecyclerView recyclerView =
				activity.findViewById(
						R.id.recyclerview_skills
				);

		if (combos == null || combos.length() == 0) {
			setVisibility(section, View.GONE);
			return;
		}

		ArrayList<HeroComboStepItem> steps =
				new ArrayList<>();

		String sectionDescription = "";

		for (int i = 0; i < combos.length(); i++) {

			JSONObject combo =
					combos.optJSONObject(i);

			if (combo == null) {
				continue;
			}

			if (sectionDescription.isEmpty()) {
				sectionDescription =
						valueOr(
								combo.optString("description"),
								"Use this combo during the laning phase."
						);
			}

			JSONArray comboSteps =
					combo.optJSONArray("steps");

			if (comboSteps == null) {
				continue;
			}

			for (int j = 0; j < comboSteps.length(); j++) {

				JSONObject stepObject =
						comboSteps.optJSONObject(j);

				if (stepObject == null) {
					continue;
				}

				HeroComboStepItem step =
						new HeroComboStepItem();

				step.step =
						stepObject.optInt("step", j + 1);

				step.label =
						valueOr(
								stepObject.optString("label"),
								"Step " + step.step
						);

				step.image =
						safe(stepObject.optString("image"));

				steps.add(step);
			}
		}

		if (steps.isEmpty()) {
			setVisibility(section, View.GONE);
			return;
		}

		setVisibility(section, View.VISIBLE);

		if (description != null) {
			description.setText(sectionDescription);
		}

		if (recyclerView != null) {

			recyclerView.setLayoutManager(
					new LinearLayoutManager(
							activity,
							LinearLayoutManager.HORIZONTAL,
							false
					)
			);

			recyclerView.setNestedScrollingEnabled(false);
			recyclerView.setAdapter(
					new HeroComboStepAdapter(
							activity,
							steps
					)
			);
		}
	}

	private static void bindCounterList(
			Activity activity,
			JSONArray array,
			int sectionId,
			int recyclerId) {

		View section =
				activity.findViewById(sectionId);

		RecyclerView recyclerView =
				activity.findViewById(recyclerId);

		if (array == null || array.length() == 0) {
			setVisibility(section, View.GONE);
			return;
		}

		ArrayList<HeroCounterItem> list =
				new ArrayList<>();

		for (int i = 0; i < array.length(); i++) {

			JSONObject object =
					array.optJSONObject(i);

			if (object == null) {
				continue;
			}

			HeroCounterItem item =
					new HeroCounterItem();

			item.id =
					safe(object.optString("id"));

			item.name =
					safe(object.optString("name"));

			item.image =
					safe(object.optString("image"));

			item.icon =
					safe(object.optString("icon"));

			item.role =
					cleanRoleValue(
							object.opt("roles")
					);

			if (item.role.isEmpty()) {
				item.role =
						safe(object.optString("role"));
			}

			item.tier =
					safe(object.optString("tier"));

			item.winRate =
					readDouble(
							object,
							"win_rate"
					);

			item.delta =
					readDouble(
							object,
							"delta"
					);

			list.add(item);
		}

		if (list.isEmpty()) {
			setVisibility(section, View.GONE);
			return;
		}

		setVisibility(section, View.VISIBLE);

		if (recyclerView != null) {

			recyclerView.setVisibility(View.VISIBLE);

			android.view.ViewGroup.LayoutParams params =
					recyclerView.getLayoutParams();

			if (params != null) {
				params.height = dp(activity, 118);
				recyclerView.setLayoutParams(params);
			}

			recyclerView.setLayoutManager(
					new LinearLayoutManager(
							activity,
							LinearLayoutManager.HORIZONTAL,
							false
					)
			);

			recyclerView.setNestedScrollingEnabled(false);
			recyclerView.setAdapter(
					new HeroCounterAdapter(
							activity,
							list
					)
			);
		}
	}

	private static String firstArrayValue(
			JSONArray array) {

		if (array == null || array.length() == 0) {
			return "";
		}

		for (int i = 0; i < array.length(); i++) {
			String value = safe(array.optString(i));
			if (!value.isEmpty()) {
				return value;
			}
		}

		return "";
	}

	private static String cleanRoleValue(
			Object value) {

		if (value == null || value == JSONObject.NULL) {
			return "";
		}

		if (value instanceof JSONArray) {

			JSONArray array =
					(JSONArray) value;

			StringBuilder result =
					new StringBuilder();

			for (int i = 0; i < array.length(); i++) {

				String role =
						safe(array.optString(i));

				if (role.isEmpty()) {
					continue;
				}

				if (result.length() > 0) {
					result.append(" / ");
				}

				result.append(role);
			}

			return result.toString();
		}

		String text = safe(String.valueOf(value));

		if ("[]".equals(text) ||
				"null".equalsIgnoreCase(text)) {
			return "";
		}

		return text;
	}

	private static double readDouble(
			JSONObject object,
			String key) {

		if (object == null || object.isNull(key)) {
			return Double.NaN;
		}

		Object value =
				object.opt(key);

		if (value instanceof Number) {
			return ((Number) value).doubleValue();
		}

		try {
			return Double.parseDouble(
					String.valueOf(value)
			);
		} catch (Exception ignored) {
			return Double.NaN;
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
				.replace("â€™", "")
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