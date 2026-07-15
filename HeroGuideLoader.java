package com.iandev.masterkit;

import android.app.Activity;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;

public class HeroGuideLoader {

	private static final String API_URL =
			"https://maskitkit-api.vercel.app/api/hero?id=";

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
			if (!heroName.isEmpty()) {
				toolbarTitle.setText(heroName + " Guide");
			} else {
				toolbarTitle.setText("Hero Guide");
			}
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

				HttpURLConnection connection = null;

				try {
					String encodedId =
							URLEncoder.encode(heroId, "UTF-8");

					URL url = new URL(API_URL + encodedId);

					connection =
							(HttpURLConnection) url.openConnection();

					connection.setRequestMethod("GET");
					connection.setConnectTimeout(15000);
					connection.setReadTimeout(25000);
					connection.setUseCaches(false);

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

					InputStream inputStream;

					if (responseCode >= 200 &&
							responseCode < 300) {

						inputStream =
								connection.getInputStream();

					} else {

						inputStream =
								connection.getErrorStream();
					}

					String response =
							readStream(inputStream);

					if (responseCode < 200 ||
							responseCode >= 300) {

						String message =
								extractError(response);

						return Result.error(message);
					}

					JSONObject root =
							new JSONObject(response);

					if (!root.optBoolean("success", false)) {

						return Result.error(
								root.optString(
										"message",
										"Unable to load hero guide."
								)
						);
					}

					JSONObject hero =
							root.optJSONObject("hero");

					if (hero == null) {
						return Result.error(
								"Hero data was not found."
						);
					}

					return Result.success(hero);

				} catch (Exception e) {

					return Result.error(
							e.getMessage() == null
									? "Connection failed."
									: e.getMessage()
					);

				} finally {

					if (connection != null) {
						connection.disconnect();
					}
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

		String specialtyText = specialty;

		if (specialtyText.isEmpty()) {
			specialtyText = difficulty;
		}

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

		String heroImage = image;

		if (heroImage.isEmpty()) {
			heroImage = backdrop;
		}

		loadImage(
				activity,
				R.id.imageview_hero_background,
				heroImage
		);

		bindBuild(activity, hero.optJSONObject("build"));

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

			setVisibility(
					buildSection,
					View.GONE
			);

			setVisibility(
					setupSection,
					View.GONE
			);

			return;
		}

		JSONArray items =
				build.optJSONArray("items");

		String emblem =
				safe(build.optString("emblem"));

		String emblemImage =
				safe(build.optString("emblem_image"));

		String spell =
				safe(build.optString("spell"));

		String spellImage =
				safe(build.optString("spell_image"));

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

		View emblemCard =
				activity.findViewById(
						R.id.linear_emblem
				);

		View spellCard =
				activity.findViewById(
						R.id.linear_spell
				);

		boolean hasEmblem =
				!emblem.isEmpty();

		boolean hasSpell =
				!spell.isEmpty();

		setVisibility(
				emblemCard,
				hasEmblem
						? View.VISIBLE
						: View.GONE
		);

		setVisibility(
				spellCard,
				hasSpell
						? View.VISIBLE
						: View.GONE
		);

		if (hasEmblem) {

			setText(
					activity,
					R.id.textview_emblem,
					emblem
			);

			if (!emblemImage.isEmpty()) {
				loadImage(
						activity,
						R.id.imageview_emblem,
						emblemImage
				);
			}
		}

		if (hasSpell) {

			setText(
					activity,
					R.id.textview_spell,
					spell
			);

			if (!spellImage.isEmpty()) {
				loadImage(
						activity,
						R.id.imageview_spell,
						spellImage
				);
			}
		}

		/*
		 * Kapag spell lang ang available, full width ang spell card.
		 * Kapag parehong available, tig-kalahati sila.
		 */
		updateSetupCardWidths(
				emblemCard,
				spellCard,
				hasEmblem,
				hasSpell
		);

		if (hasItems) {
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

				item.name = safe(
						itemObject.optString("name")
				);

				item.image = safe(
						itemObject.optString("image")
				);

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
	}

	private static void updateSetupCardWidths(
			View emblemCard,
			View spellCard,
			boolean hasEmblem,
			boolean hasSpell) {

		if (emblemCard != null) {

			LinearLayout.LayoutParams params =
					(LinearLayout.LayoutParams)
							emblemCard.getLayoutParams();

			if (params != null) {

				params.width = 0;
				params.weight =
						hasEmblem && hasSpell
								? 1f
								: hasEmblem
										? 1f
										: 0f;

				emblemCard.setLayoutParams(params);
			}
		}

		if (spellCard != null) {

			LinearLayout.LayoutParams params =
					(LinearLayout.LayoutParams)
							spellCard.getLayoutParams();

			if (params != null) {

				params.width = 0;
				params.weight =
						hasSpell
								? 1f
								: 0f;

				params.leftMargin =
						hasEmblem && hasSpell
								? dp(spellCard, 10)
								: 0;

				spellCard.setLayoutParams(params);
			}
		}
	}

	private static int dp(
			View view,
			int value) {

		if (view == null) {
			return value;
		}

		return (int) (
				value *
				view.getResources()
						.getDisplayMetrics()
						.density +
				0.5f
		);
	}

	private static void bindLaningCombos(
			Activity activity,
			JSONArray combos) {

		View section =
				activity.findViewById(
						R.id.linear_skills_section
				);

		RecyclerView recyclerView =
				activity.findViewById(
						R.id.recyclerview_skills
				);

		if (combos == null
				|| combos.length() == 0
				|| recyclerView == null) {

			setVisibility(section, View.GONE);
			return;
		}

		JSONObject combo =
				combos.optJSONObject(0);

		if (combo == null) {
			setVisibility(section, View.GONE);
			return;
		}

		String title =
				safe(combo.optString("title"));

		String description =
				safe(combo.optString("description"));

		JSONArray steps =
				combo.optJSONArray("steps");

		if (!title.isEmpty()) {
			setText(
					activity,
					R.id.textview31,
					title
			);
		}

		setText(
				activity,
				R.id.textview_combo_description,
				valueOr(
						description,
						"Follow the recommended combo order during the laning phase."
				)
		);

		if (steps == null
				|| steps.length() == 0) {

			setVisibility(section, View.GONE);
			return;
		}

		ArrayList<HeroComboStepItem> comboList =
				new ArrayList<>();

		for (int i = 0; i < steps.length(); i++) {

			JSONObject stepObject =
					steps.optJSONObject(i);

			if (stepObject == null) {
				continue;
			}

			HeroComboStepItem item =
					new HeroComboStepItem();

			item.step =
					stepObject.optInt(
							"step",
							i + 1
					);

			item.label =
					safe(
							stepObject.optString(
									"label"
							)
					);

			item.image =
					safe(
							stepObject.optString(
									"image"
							)
					);

			comboList.add(item);
		}

		if (comboList.isEmpty()) {
			setVisibility(section, View.GONE);
			return;
		}

		recyclerView.setLayoutManager(
				new LinearLayoutManager(
						activity,
						LinearLayoutManager.HORIZONTAL,
						false
				)
		);

		recyclerView.setAdapter(
				new HeroComboStepAdapter(
						activity,
						comboList
				)
		);

		recyclerView.setNestedScrollingEnabled(false);
		setVisibility(section, View.VISIBLE);
	}

	private static void bindCounterList(
			Activity activity,
			JSONArray array,
			int sectionId,
			int recyclerViewId) {

		View section =
				activity.findViewById(sectionId);

		RecyclerView recyclerView =
				activity.findViewById(recyclerViewId);

		if (array == null
				|| array.length() == 0
				|| recyclerView == null) {

			setVisibility(section, View.GONE);
			return;
		}

		ArrayList<HeroCounterItem> counterList =
				new ArrayList<>();

		for (int i = 0; i < array.length(); i++) {

			JSONObject object =
					array.optJSONObject(i);

			if (object == null) {
				continue;
			}

			HeroCounterItem item =
					new HeroCounterItem();

			item.id = safe(
					object.optString("id")
			);

			item.name = safe(
					object.optString("name")
			);

			item.image = safe(
					object.optString("image")
			);

			item.role = safe(
					object.optString("role")
			);

			item.tier = safe(
					object.optString("tier")
			);

			if (!object.isNull("win_rate")) {
				item.winRate = object.optDouble(
						"win_rate",
						Double.NaN
				);
			}

			if (!object.isNull("delta")) {
				item.delta = object.optDouble(
						"delta",
						Double.NaN
				);
			}

			if (!item.id.isEmpty()
					|| !item.name.isEmpty()) {

				counterList.add(item);
			}
		}

		if (counterList.isEmpty()) {
			setVisibility(section, View.GONE);
			return;
		}

		recyclerView.setLayoutManager(
				new LinearLayoutManager(
						activity,
						LinearLayoutManager.HORIZONTAL,
						false
				)
		);

		recyclerView.setAdapter(
				new HeroCounterAdapter(
						activity,
						counterList
				)
		);

		recyclerView.setNestedScrollingEnabled(false);
		setVisibility(section, View.VISIBLE);
	}

	private static void bindArraySection(
			Activity activity,
			JSONArray array,
			int sectionId) {

		View section =
				activity.findViewById(sectionId);

		boolean hasData =
				array != null &&
				array.length() > 0;

		setVisibility(
				section,
				hasData ? View.VISIBLE : View.GONE
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

		String formatted;

		if (value == Math.floor(value)) {
			formatted =
					String.valueOf((int) value);
		} else {
			formatted =
					String.valueOf(value);
		}

		textView.setText(formatted + "%");
	}

	private static void loadImage(
			Activity activity,
			int imageViewId,
			String imageUrl) {

		ImageView imageView =
				activity.findViewById(imageViewId);

		if (imageView == null ||
				imageUrl.isEmpty()) {

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

		setVisible(
				activity,
				R.id.linear_loading,
				true
		);

		setVisible(
				activity,
				R.id.linear_error,
				false
		);

		setVisible(
				activity,
				R.id.vscroll_hero,
				false
		);
	}

	private static void showContent(
			Activity activity) {

		setVisible(
				activity,
				R.id.linear_loading,
				false
		);

		setVisible(
				activity,
				R.id.linear_error,
				false
		);

		setVisible(
				activity,
				R.id.vscroll_hero,
				true
		);
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

		setVisible(
				activity,
				R.id.linear_loading,
				false
		);

		setVisible(
				activity,
				R.id.vscroll_hero,
				false
		);

		setVisible(
				activity,
				R.id.linear_error,
				true
		);
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

	private static String formatHeroName(
			String id) {

		if (id == null || id.trim().isEmpty()) {
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