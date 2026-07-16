package com.iandev.masterkit;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Outline;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.ViewOutlineProvider;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Locale;

public class HeroCounterAdapter
		extends RecyclerView.Adapter<HeroCounterAdapter.Holder> {

	private static final String HERO_API =
			"https://maskitkit-api.vercel.app/api/hero?id=";

	private static final HashMap<String, String>
			PORTRAIT_CACHE = new HashMap<>();

	private final Activity activity;
	private final ArrayList<HeroCounterItem> list;

	public HeroCounterAdapter(
			Activity activity,
			ArrayList<HeroCounterItem> list) {

		this.activity = activity;
		this.list = list;
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(
			@NonNull ViewGroup parent,
			int viewType) {

		View view = LayoutInflater
				.from(activity)
				.inflate(
						R.layout.hero_counter_item,
						parent,
						false
				);

		return new Holder(view);
	}

	@Override
	public void onBindViewHolder(
			@NonNull Holder holder,
			int position) {

		final HeroCounterItem item = list.get(position);

		holder.name.setText(
				item.name == null ? "" : item.name
		);

		StringBuilder meta = new StringBuilder();

		if (item.tier != null
				&& item.tier.trim().length() > 0) {

			meta.append("Tier ")
					.append(item.tier.trim());
		}

		if (item.role != null
				&& item.role.trim().length() > 0) {

			if (meta.length() > 0) {
				meta.append(" • ");
			}

			meta.append(item.role.trim());
		}

		holder.meta.setText(meta.toString());
		holder.meta.setVisibility(
				meta.length() > 0
						? View.VISIBLE
						: View.GONE
		);

		if (!Double.isNaN(item.winRate)) {

			holder.rate.setText(
					formatNumber(item.winRate) + "%"
			);

			holder.rate.setVisibility(View.VISIBLE);

		} else {

			holder.rate.setVisibility(View.GONE);
		}

		applyCircle(holder.image);

		String cachedPortrait = "";

		synchronized (PORTRAIT_CACHE) {

			if (item.id != null) {

				String value =
						PORTRAIT_CACHE.get(
								item.id.trim()
						);

				if (value != null) {
					cachedPortrait = value;
				}
			}
		}

		/*
		 * Always display the current API image first.
		 * A validated Wiki icon may replace it asynchronously.
		 */
		String initialImage =
				cachedPortrait.length() > 0
						? cachedPortrait
						: item.icon != null
						&& item.icon.trim().length() > 0
								? item.icon
								: item.image;

		SimpleImageLoader.load(
				initialImage,
				holder.image,
				R.drawable.des
		);

		/*
		 * Counter API currently returns wide artwork.
		 * Kunin ang backdrop ng mismong hero guide para
		 * head/portrait icon ang lumabas dito.
		 */
		loadPortrait(
				holder,
				item
		);

		holder.itemView.setOnClickListener(
				new View.OnClickListener() {
					@Override
					public void onClick(View view) {

						if (item.id == null
								|| item.id.trim().length() == 0) {
							return;
						}

						Intent intent = new Intent(
								activity,
								HeroguideActivity.class
						);

						intent.putExtra(
								"hero_id",
								item.id
						);

						intent.putExtra(
								"hero_name",
								item.name
						);

						activity.startActivity(intent);
					}
				}
		);
	}

	private void loadPortrait(
			final Holder holder,
			final HeroCounterItem item) {

		if (holder == null || item == null) {
			return;
		}

		final String heroKey =
				item.name == null
						? ""
						: item.name.trim().toLowerCase(Locale.US);

		if (heroKey.isEmpty()) {
			return;
		}

		holder.image.setTag(heroKey);

		new AsyncTask<Void, Void, String>() {

			@Override
			protected String doInBackground(Void... ignored) {
				return WikiHeroImageResolver.getIconBlocking(
						item.name
				);
			}

			@Override
			protected void onPostExecute(String icon) {

				if (icon == null
						|| icon.trim().isEmpty()
						|| activity == null
						|| activity.isFinishing()) {
					return;
				}

				Object tag = holder.image.getTag();

				if (tag == null
						|| !heroKey.equals(String.valueOf(tag))) {
					return;
				}

				SimpleImageLoader.load(
						icon,
						holder.image,
						R.drawable.des
				);
			}
		}.execute();
	}

	private void applyCircle(final ImageView imageView) {

		if (imageView == null) {
			return;
		}

		imageView.setScaleType(
				ImageView.ScaleType.CENTER_CROP
		);

		if (android.os.Build.VERSION.SDK_INT >= 21) {

			imageView.setClipToOutline(true);

			imageView.setOutlineProvider(
					new ViewOutlineProvider() {
						@Override
						public void getOutline(
								View view,
								Outline outline) {

							outline.setOval(
									0,
									0,
									view.getWidth(),
									view.getHeight()
							);
						}
					}
			);
		}
	}

	private String formatNumber(double value) {

		if (value == Math.floor(value)) {
			return String.valueOf((int) value);
		}

		return String.format(
				Locale.US,
				"%.2f",
				value
		).replaceAll("0+$", "")
				.replaceAll("\\.$", "");
	}

	@Override
	public int getItemCount() {
		return list == null ? 0 : list.size();
	}

	public static class Holder
			extends RecyclerView.ViewHolder {

		ImageView image;
		TextView name;
		TextView meta;
		TextView rate;

		public Holder(View itemView) {
			super(itemView);

			image = itemView.findViewById(
					R.id.imageview_counter
			);

			name = itemView.findViewById(
					R.id.textview_counter_name
			);

			meta = itemView.findViewById(
					R.id.textview_counter_meta
			);

			rate = itemView.findViewById(
					R.id.textview_counter_rate
			);
		}
	}
}
