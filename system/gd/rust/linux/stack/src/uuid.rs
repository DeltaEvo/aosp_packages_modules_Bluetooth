//! Collection of Profile UUIDs and helpers to use them.

use std::collections::{HashMap, HashSet};
use std::fmt::{Display, Formatter};

use bt_topshim::btif::{Uuid, Uuid128Bit};

// List of profile uuids
pub const A2DP_SINK: &str = "0000110B-0000-1000-8000-00805F9B34FB";
pub const A2DP_SOURCE: &str = "0000110A-0000-1000-8000-00805F9B34FB";
pub const ADV_AUDIO_DIST: &str = "0000110D-0000-1000-8000-00805F9B34FB";
pub const BAS: &str = "0000180F-0000-1000-8000-00805F9B34FB";
pub const HSP: &str = "00001108-0000-1000-8000-00805F9B34FB";
pub const HSP_AG: &str = "00001112-0000-1000-8000-00805F9B34FB";
pub const HFP: &str = "0000111E-0000-1000-8000-00805F9B34FB";
pub const HFP_AG: &str = "0000111F-0000-1000-8000-00805F9B34FB";
pub const AVRCP_CONTROLLER: &str = "0000110E-0000-1000-8000-00805F9B34FB";
pub const AVRCP_TARGET: &str = "0000110C-0000-1000-8000-00805F9B34FB";
pub const OBEX_OBJECT_PUSH: &str = "00001105-0000-1000-8000-00805f9b34fb";
pub const HID: &str = "00001124-0000-1000-8000-00805f9b34fb";
pub const HOGP: &str = "00001812-0000-1000-8000-00805f9b34fb";
pub const PANU: &str = "00001115-0000-1000-8000-00805F9B34FB";
pub const NAP: &str = "00001116-0000-1000-8000-00805F9B34FB";
pub const BNEP: &str = "0000000f-0000-1000-8000-00805F9B34FB";
pub const PBAP_PCE: &str = "0000112e-0000-1000-8000-00805F9B34FB";
pub const PBAP_PSE: &str = "0000112f-0000-1000-8000-00805F9B34FB";
pub const MAP: &str = "00001134-0000-1000-8000-00805F9B34FB";
pub const MNS: &str = "00001133-0000-1000-8000-00805F9B34FB";
pub const MAS: &str = "00001132-0000-1000-8000-00805F9B34FB";
pub const SAP: &str = "0000112D-0000-1000-8000-00805F9B34FB";
pub const HEARING_AID: &str = "0000FDF0-0000-1000-8000-00805f9b34fb";
pub const LE_AUDIO: &str = "EEEEEEEE-EEEE-EEEE-EEEE-EEEEEEEEEEEE";
pub const DIP: &str = "00001200-0000-1000-8000-00805F9B34FB";
pub const VOLUME_CONTROL: &str = "00001844-0000-1000-8000-00805F9B34FB";
pub const GENERIC_MEDIA_CONTROL: &str = "00001849-0000-1000-8000-00805F9B34FB";
pub const MEDIA_CONTROL: &str = "00001848-0000-1000-8000-00805F9B34FB";
pub const COORDINATED_SET: &str = "00001846-0000-1000-8000-00805F9B34FB";
pub const BASE_UUID: &str = "00000000-0000-1000-8000-00805F9B34FB";

/// List of profiles that with known uuids.
#[derive(Clone, Debug, Hash, PartialEq, PartialOrd, Eq, FromPrimitive, ToPrimitive, Copy)]
#[repr(u32)]
pub enum Profile {
    A2dpSink,
    A2dpSource,
    AdvAudioDist,
    Bas,
    Hsp,
    HspAg,
    Hfp,
    HfpAg,
    AvrcpController,
    AvrcpTarget,
    ObexObjectPush,
    Hid,
    Hogp,
    Panu,
    Nap,
    Bnep,
    PbapPce,
    PbapPse,
    Map,
    Mns,
    Mas,
    Sap,
    HearingAid,
    LeAudio,
    Dip,
    VolumeControl,
    GenericMediaControl,
    MediaControl,
    CoordinatedSet,
}

// Unsigned integer representation of UUIDs.
pub const BASE_UUID_NUM: u128 = 0x0000000000001000800000805f9b34fbu128;
pub const BASE_UUID_MASK: u128 = !(0xffffffffu128 << 96);

/// Wraps a reference of Uuid128Bit, which is the raw array of bytes of UUID.
/// This is useful in implementing standard Rust traits which can't be implemented directly on
/// built-in types (Rust's Orphan Rule).
pub struct UuidWrapper<'a>(pub &'a Uuid128Bit);

impl<'a> Display for UuidWrapper<'a> {
    fn fmt(&self, f: &mut Formatter) -> std::fmt::Result {
        Uuid::format(&self.0, f)
    }
}

pub struct KnownUuidWrapper<'a>(pub &'a Uuid128Bit, pub &'a Profile);

impl<'a> Display for KnownUuidWrapper<'a> {
    fn fmt(&self, f: &mut Formatter) -> std::fmt::Result {
        let _ = Uuid::format(&self.0, f);
        write!(f, ": {:?}", self.1)
    }
}

/// Represents binary encoding of UUID in big-endian.
#[derive(Debug)]
pub enum UuidBytes {
    Uuid16Bit([u8; 2]),
    Uuid32Bit([u8; 4]),
    Uuid128Bit([u8; 16]),
}

pub struct UuidHelper {
    /// A list of enabled profiles on the system. These may be modified by policy.
    pub enabled_profiles: HashSet<Profile>,

    /// Map a UUID to a known profile
    pub profiles: HashMap<Uuid128Bit, Profile>,
}

impl UuidHelper {
    pub fn new() -> Self {
        let enabled_profiles: HashSet<Profile> = [
            Profile::A2dpSink,
            Profile::A2dpSource,
            Profile::Hsp,
            Profile::Hfp,
            Profile::Hid,
            Profile::Hogp,
            Profile::Panu,
            Profile::PbapPce,
            Profile::Map,
            Profile::HearingAid,
            Profile::VolumeControl,
            Profile::CoordinatedSet,
        ]
        .iter()
        .cloned()
        .collect();

        let profiles: HashMap<Uuid128Bit, Profile> = [
            (UuidHelper::from_string(A2DP_SINK).unwrap(), Profile::A2dpSink),
            (UuidHelper::from_string(A2DP_SOURCE).unwrap(), Profile::A2dpSource),
            (UuidHelper::from_string(ADV_AUDIO_DIST).unwrap(), Profile::AdvAudioDist),
            (UuidHelper::from_string(HSP).unwrap(), Profile::Hsp),
            (UuidHelper::from_string(HSP_AG).unwrap(), Profile::HspAg),
            (UuidHelper::from_string(HFP).unwrap(), Profile::Hfp),
            (UuidHelper::from_string(HFP_AG).unwrap(), Profile::HfpAg),
            (UuidHelper::from_string(AVRCP_CONTROLLER).unwrap(), Profile::AvrcpController),
            (UuidHelper::from_string(AVRCP_TARGET).unwrap(), Profile::AvrcpTarget),
            (UuidHelper::from_string(OBEX_OBJECT_PUSH).unwrap(), Profile::ObexObjectPush),
            (UuidHelper::from_string(HID).unwrap(), Profile::Hid),
            (UuidHelper::from_string(HOGP).unwrap(), Profile::Hogp),
            (UuidHelper::from_string(PANU).unwrap(), Profile::Panu),
            (UuidHelper::from_string(NAP).unwrap(), Profile::Nap),
            (UuidHelper::from_string(BNEP).unwrap(), Profile::Bnep),
            (UuidHelper::from_string(PBAP_PCE).unwrap(), Profile::PbapPce),
            (UuidHelper::from_string(PBAP_PSE).unwrap(), Profile::PbapPse),
            (UuidHelper::from_string(MAP).unwrap(), Profile::Map),
            (UuidHelper::from_string(MNS).unwrap(), Profile::Mns),
            (UuidHelper::from_string(MAS).unwrap(), Profile::Mas),
            (UuidHelper::from_string(SAP).unwrap(), Profile::Sap),
            (UuidHelper::from_string(HEARING_AID).unwrap(), Profile::HearingAid),
            (UuidHelper::from_string(LE_AUDIO).unwrap(), Profile::LeAudio),
            (UuidHelper::from_string(DIP).unwrap(), Profile::Dip),
            (UuidHelper::from_string(VOLUME_CONTROL).unwrap(), Profile::VolumeControl),
            (UuidHelper::from_string(GENERIC_MEDIA_CONTROL).unwrap(), Profile::GenericMediaControl),
            (UuidHelper::from_string(MEDIA_CONTROL).unwrap(), Profile::MediaControl),
            (UuidHelper::from_string(COORDINATED_SET).unwrap(), Profile::CoordinatedSet),
        ]
        .iter()
        .cloned()
        .collect();

        UuidHelper { enabled_profiles, profiles }
    }

    /// Checks whether a UUID corresponds to a currently enabled profile.
    pub fn is_profile_enabled(&self, profile: &Profile) -> bool {
        self.enabled_profiles.contains(profile)
    }

    /// Converts a UUID to a known profile enum.
    pub fn is_known_profile(&self, uuid: &Uuid128Bit) -> Option<&Profile> {
        self.profiles.get(uuid)
    }

    pub fn get_enabled_profiles(&self) -> HashSet<Profile> {
        self.enabled_profiles.clone()
    }

    /// Converts a UUID byte array into a formatted string.
    pub fn to_string(uuid: &Uuid128Bit) -> String {
        UuidWrapper(&uuid).to_string()
    }

    /// If a uuid is known to be a certain service, convert it into a formatted
    /// string that shows the service name. Else just format the uuid.
    pub fn known_uuid_to_string(&self, uuid: &Uuid128Bit) -> String {
        if let Some(p) = self.is_known_profile(uuid) {
            return KnownUuidWrapper(&uuid, &p).to_string();
        }

        UuidHelper::to_string(uuid)
    }

    /// Converts a well-formatted UUID string to a UUID byte array.
    /// The UUID string should be in the format:
    /// 12345678-1234-1234-1234-1234567890
    pub fn from_string<S: Into<String>>(raw: S) -> Option<Uuid128Bit> {
        let raw: String = raw.into();

        // Make sure input is valid length and formatting
        let s = raw.split('-').collect::<Vec<&str>>();
        if s.len() != 5 || raw.len() != 36 {
            return None;
        }

        let mut uuid: Uuid128Bit = [0; 16];
        let mut idx = 0;
        for section in s.iter() {
            for i in (0..section.len()).step_by(2) {
                uuid[idx] = match u8::from_str_radix(&section[i..i + 2], 16) {
                    Ok(res) => res,
                    Err(_) => {
                        return None;
                    }
                };
                idx = idx + 1;
            }
        }

        Some(uuid)
    }

    /// Parses an 128-bit UUID into a byte array of shortest representation.
    pub fn get_shortest_bytes(uuid: &Uuid128Bit) -> UuidBytes {
        if UuidHelper::in_16bit_uuid_range(uuid) {
            return UuidBytes::Uuid16Bit([uuid[2], uuid[3]]);
        } else if UuidHelper::in_32bit_uuid_range(uuid) {
            return UuidBytes::Uuid32Bit([uuid[0], uuid[1], uuid[2], uuid[3]]);
        } else {
            return UuidBytes::Uuid128Bit(*uuid);
        }
    }

    /// Checks whether the UUID value is in the 16-bit Bluetooth UUID range.
    fn in_16bit_uuid_range(uuid: &Uuid128Bit) -> bool {
        if !UuidHelper::in_32bit_uuid_range(uuid) {
            return false;
        }
        uuid[0] == 0 && uuid[1] == 0
    }

    /// Checks whether the UUID value is in the 32-bit Bluetooth UUID range.
    fn in_32bit_uuid_range(uuid: &Uuid128Bit) -> bool {
        let num = u128::from_be_bytes(*uuid);
        (num & BASE_UUID_MASK) == BASE_UUID_NUM
    }
}

// Temporary util that covers only basic string conversion.
// TODO(b/193685325): Implement more UUID utils by using Uuid from gd/hci/uuid.h with cxx.
pub fn parse_uuid_string<T: Into<String>>(uuid: T) -> Option<Uuid> {
    let uuid = uuid.into();

    // Strip un-needed characters before parsing to handle the common
    // case of including dashes in UUID strings. UUID expects only
    // 0-9, a-f, A-F with no other characters. |is_digit| with radix
    // 16 (hex) supports that exact behavior.
    let uuid = uuid.chars().filter(|char| char.is_digit(16)).collect::<String>();
    if uuid.len() != 32 {
        return None;
    }

    let mut raw = [0; 16];

    for i in 0..16 {
        raw[i] = u8::from_str_radix(&uuid[i * 2..i * 2 + 2], 16).ok()?;
    }

    Some(Uuid { uu: raw })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_uuidhelper() {
        let uuidhelper = UuidHelper::new();
        for (uuid, _) in uuidhelper.profiles.iter() {
            let converted = UuidHelper::from_string(UuidHelper::to_string(&uuid));
            assert_eq!(converted.is_some(), true);
            converted.and_then::<Uuid128Bit, _>(|uu: Uuid128Bit| {
                assert_eq!(&uu, uuid);
                None
            });
        }
    }

    #[test]
    fn test_get_shortest_bytes() {
        let uuid_16 = UuidHelper::from_string("0000fef3-0000-1000-8000-00805f9b34fb").unwrap();
        assert!(matches!(
            UuidHelper::get_shortest_bytes(&uuid_16),
            UuidBytes::Uuid16Bit([0xfe, 0xf3])
        ));

        let uuid_32 = UuidHelper::from_string("00112233-0000-1000-8000-00805f9b34fb").unwrap();
        assert!(matches!(
            UuidHelper::get_shortest_bytes(&uuid_32),
            UuidBytes::Uuid32Bit([0x00, 0x11, 0x22, 0x33])
        ));

        let uuid_128 = UuidHelper::from_string("00112233-4455-6677-8899-aabbccddeeff").unwrap();
        let exp_bytes: Vec<u8> = (0..16).map(|d| (d << 4) + d).collect();
        assert!(matches!(
            UuidHelper::get_shortest_bytes(&uuid_128),
            UuidBytes::Uuid128Bit(exp_bytes)
        ));
    }
}
