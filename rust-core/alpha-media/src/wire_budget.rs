//! Keeps the media part of one message within what every relay accepts.

use crate::media_ref::MediaRef;

/// The media part of one message on the wire. Relays refuse events over 64 KB, and
/// encryption and framing grow the payload by about a third.
pub const MEDIA_WIRE_BUDGET: usize = 30 * 1024;
/// About what a ref weighs once its chunk list moved into a manifest.
const SLIM_REF_BYTES: usize = 1024;

/// The item's weight on the wire without its preview; `None` if it cannot get lighter.
pub fn body_len(r: &MediaRef) -> Option<usize> {
    if r.is_external_manifest() {
        return None;
    }
    let mut bare = r.clone();
    bare.poster_b64 = None;
    bare.local_path = None;
    serde_json::to_string(&bare).ok().map(|s| s.len())
}

/// Which items move their chunk lists into a manifest, heaviest first, until the
/// bodies take at most half the budget and leave the rest to the previews.
pub fn items_to_externalize(bodies: &[Option<usize>], budget: usize) -> Vec<bool> {
    let mut out = vec![false; bodies.len()];
    let mut total: usize = bodies.iter().map(|b| b.unwrap_or(SLIM_REF_BYTES)).sum();
    let mut order: Vec<usize> = (0..bodies.len()).filter(|&k| bodies[k].is_some_and(|b| b > SLIM_REF_BYTES)).collect();
    order.sort_by_key(|&k| std::cmp::Reverse(bodies[k]));
    for k in order {
        if total <= budget / 2 {
            break;
        }
        out[k] = true;
        total -= bodies[k].unwrap_or(SLIM_REF_BYTES) - SLIM_REF_BYTES;
    }
    out
}

/// Shrinks the items' previews (`poster_b64`) until the list fits `budget`: each gets an
/// even share of the room the rest leaves, and a preview within its share stays as it is.
pub fn fit_previews(items: &mut [serde_json::Value], budget: usize) {
    let total = serde_json::to_string(&*items).map(|s| s.len()).unwrap_or(0);
    if total <= budget {
        return;
    }
    let lens: Vec<usize> = items.iter().map(|it| preview(it).map_or(0, str::len)).collect();
    let mut room = budget.saturating_sub(total - lens.iter().sum::<usize>());
    let mut order: Vec<usize> = (0..items.len()).filter(|&k| lens[k] > 0).collect();
    order.sort_by_key(|&k| lens[k]);
    let mut left = order.len();
    for k in order {
        let share = room / left;
        left -= 1;
        if lens[k] <= share {
            room -= lens[k];
            continue;
        }
        let Some(obj) = items[k].as_object_mut() else { continue };
        let smaller = preview(&serde_json::Value::Object(obj.clone()))
            .and_then(|p| crate::thumbnail::fit_poster_b64(p, share));
        match smaller {
            Some(p) => {
                room = room.saturating_sub(p.len());
                obj.insert("poster_b64".into(), serde_json::Value::String(p));
            }
            None => {
                obj.remove("poster_b64");
            }
        }
    }
}

fn preview(item: &serde_json::Value) -> Option<&str> {
    item.get("poster_b64")?.as_str()
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine;

    fn photo_b64(w: u32, h: u32) -> String {
        let mut seed = 0x9E37_79B9u32;
        let img = image::RgbImage::from_fn(w, h, |_, _| {
            seed ^= seed << 13;
            seed ^= seed >> 17;
            seed ^= seed << 5;
            image::Rgb([seed as u8, (seed >> 8) as u8, (seed >> 16) as u8])
        });
        let mut jpeg = Vec::new();
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, 70)
            .encode(img.as_raw(), w, h, image::ExtendedColorType::Rgb8)
            .unwrap();
        base64::engine::general_purpose::STANDARD.encode(jpeg)
    }

    fn item(poster: &str) -> serde_json::Value {
        serde_json::json!({
            "key": "k".repeat(44), "hash": "h".repeat(64), "media_type": "image",
            "blob_urls": ["https://blossom.example/".to_string() + &"a".repeat(64)],
            "poster_b64": poster,
        })
    }

    fn size(items: &[serde_json::Value]) -> usize {
        serde_json::to_string(items).unwrap().len()
    }

    #[test]
    fn a_light_album_is_left_as_it_is() {
        let mut items = vec![item("abc"), item("def")];
        let before = items.clone();
        fit_previews(&mut items, MEDIA_WIRE_BUDGET);
        assert_eq!(items, before);
    }

    #[test]
    fn five_photos_fit_the_budget_and_keep_their_shape() {
        let mut items: Vec<_> = (0..5).map(|_| item(&photo_b64(320, 240))).collect();
        assert!(size(&items) > 3 * MEDIA_WIRE_BUDGET);
        fit_previews(&mut items, MEDIA_WIRE_BUDGET);
        assert!(size(&items) <= MEDIA_WIRE_BUDGET, "album is {} bytes", size(&items));
        for it in &items {
            let jpeg = base64::engine::general_purpose::STANDARD.decode(preview(it).unwrap()).unwrap();
            let img = image::load_from_memory(&jpeg).unwrap();
            assert_eq!(img.width() * 3, img.height() * 4, "{}x{}", img.width(), img.height());
        }
    }

    #[test]
    fn a_preview_that_is_no_image_is_dropped() {
        let mut items = vec![item(&"x".repeat(2 * MEDIA_WIRE_BUDGET))];
        fit_previews(&mut items, MEDIA_WIRE_BUDGET);
        assert!(preview(&items[0]).is_none());
    }

    #[test]
    fn the_heaviest_chunk_lists_move_out_first() {
        let plan = items_to_externalize(&[Some(20_000), Some(1_500), Some(9_000), None], MEDIA_WIRE_BUDGET);
        assert_eq!(plan, vec![true, false, false, false]);
    }

    #[test]
    fn light_bodies_stay_inline() {
        let plan = items_to_externalize(&[Some(1_500); 5], MEDIA_WIRE_BUDGET);
        assert_eq!(plan, vec![false; 5]);
    }
}
