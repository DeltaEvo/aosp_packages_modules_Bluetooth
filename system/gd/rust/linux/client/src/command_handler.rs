use std::collections::HashMap;
use std::fmt::{Display, Formatter};
use std::slice::SliceIndex;
use std::sync::{Arc, Mutex};

use crate::bt_adv::AdvSet;
use crate::callbacks::BtGattCallback;
use crate::ClientContext;
use crate::{console_red, console_yellow, print_error, print_info};
use bt_topshim::btif::{BtConnectionState, BtStatus, BtTransport};
use bt_topshim::profiles::gatt::LePhy;
use btstack::bluetooth::{BluetoothDevice, IBluetooth, IBluetoothQA};
use btstack::bluetooth_gatt::{IBluetoothGatt, ScanSettings, ScanType};
use btstack::socket_manager::{IBluetoothSocketManager, SocketResult};
use btstack::uuid::{Profile, UuidHelper, UuidWrapper};
use manager_service::iface_bluetooth_manager::IBluetoothManager;

const INDENT_CHAR: &str = " ";
const BAR1_CHAR: &str = "=";
const BAR2_CHAR: &str = "-";
const MAX_MENU_CHAR_WIDTH: usize = 72;
const GATT_CLIENT_APP_UUID: &str = "12345678123456781234567812345678";

enum CommandError {
    // Command not handled due to invalid arguments.
    InvalidArgs,
    // Command handled but failed with the given reason.
    Failed(String),
}

type CommandResult = Result<(), CommandError>;

type CommandFunction = fn(&mut CommandHandler, &Vec<String>) -> CommandResult;

fn _noop(_handler: &mut CommandHandler, _args: &Vec<String>) -> CommandResult {
    // Used so we can add options with no direct function
    // e.g. help and quit
    Ok(())
}

pub struct CommandOption {
    rules: Vec<String>,
    description: String,
    function_pointer: CommandFunction,
}

/// Handles string command entered from command line.
pub(crate) struct CommandHandler {
    context: Arc<Mutex<ClientContext>>,
    command_options: HashMap<String, CommandOption>,
}

struct DisplayList<T>(Vec<T>);

impl<T: Display> Display for DisplayList<T> {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        let _ = write!(f, "[\n");
        for item in self.0.iter() {
            let _ = write!(f, "  {}\n", item);
        }

        write!(f, "]")
    }
}

fn wrap_help_text(text: &str, max: usize, indent: usize) -> String {
    let remaining_count = std::cmp::max(
        // real_max
        std::cmp::max(max, text.chars().count())
        // take away char count
         - text.chars().count()
        // take away real_indent
         - (
             if std::cmp::max(max, text.chars().count())- text.chars().count() > indent {
                 indent
             } else {
                 0
             }),
        0,
    );

    format!("|{}{}{}|", INDENT_CHAR.repeat(indent), text, INDENT_CHAR.repeat(remaining_count))
}

// This should be called during the constructor in order to populate the command option map
fn build_commands() -> HashMap<String, CommandOption> {
    let mut command_options = HashMap::<String, CommandOption>::new();
    command_options.insert(
        String::from("adapter"),
        CommandOption {
            rules: vec![String::from(
                "adapter <enable|disable|show|discoverable|connectable|set-name>",
            )],
            description: String::from(
                "Enable/Disable/Show default bluetooth adapter. (e.g. adapter enable)\n
                 Discoverable On/Off (e.g. adapter discoverable on)\n
                 Connectable On/Off (e.g. adapter connectable on)",
            ),
            function_pointer: CommandHandler::cmd_adapter,
        },
    );
    command_options.insert(
        String::from("bond"),
        CommandOption {
            rules: vec![String::from("bond <add|remove|cancel> <address>")],
            description: String::from("Creates a bond with a device."),
            function_pointer: CommandHandler::cmd_bond,
        },
    );
    command_options.insert(
        String::from("device"),
        CommandOption {
            rules: vec![
                String::from("device <connect|disconnect|info> <address>"),
                String::from("device set-pairing-confirmation <address> <accept|reject>"),
                String::from("device set-pairing-pin <address> <pin|reject>"),
                String::from("device set-pairing-passkey <address> <passkey|reject>"),
                String::from("device set-alias <address> <new-alias>"),
            ],
            description: String::from("Take action on a remote device. (i.e. info)"),
            function_pointer: CommandHandler::cmd_device,
        },
    );
    command_options.insert(
        String::from("discovery"),
        CommandOption {
            rules: vec![String::from("discovery <start|stop>")],
            description: String::from("Start and stop device discovery. (e.g. discovery start)"),
            function_pointer: CommandHandler::cmd_discovery,
        },
    );
    command_options.insert(
        String::from("floss"),
        CommandOption {
            rules: vec![String::from("floss <enable|disable>")],
            description: String::from("Enable or disable Floss for dogfood."),
            function_pointer: CommandHandler::cmd_floss,
        },
    );
    command_options.insert(
        String::from("gatt"),
        CommandOption {
            rules: vec![
                String::from("gatt register-client"),
                String::from("gatt client-connect <address>"),
                String::from("gatt client-read-phy <address>"),
                String::from("gatt client-discover-services <address>"),
                String::from("gatt client-disconnect <address>"),
                String::from("gatt configure-mtu <address> <mtu>"),
            ],
            description: String::from("GATT tools"),
            function_pointer: CommandHandler::cmd_gatt,
        },
    );
    command_options.insert(
        String::from("le-scan"),
        CommandOption {
            rules: vec![
                String::from("le-scan register-scanner"),
                String::from("le-scan unregister-scanner <scanner-id>"),
                String::from("le-scan start-scan <scanner-id>"),
                String::from("le-scan stop-scan <scanner-id>"),
            ],
            description: String::from("LE scanning utilities."),
            function_pointer: CommandHandler::cmd_le_scan,
        },
    );
    command_options.insert(
        String::from("advertise"),
        CommandOption {
            rules: vec![
                String::from("advertise <on|off|ext>"),
                String::from("advertise set-interval <ms>"),
                String::from("advertise set-scan-rsp <enable|disable>"),
            ],
            description: String::from("Advertising utilities."),
            function_pointer: CommandHandler::cmd_advertise,
        },
    );
    command_options.insert(
        String::from("socket"),
        CommandOption {
            rules: vec![String::from("socket test")],
            description: String::from("Socket manager utilities."),
            function_pointer: CommandHandler::cmd_socket,
        },
    );
    command_options.insert(
        String::from("get-address"),
        CommandOption {
            rules: vec![String::from("get-address")],
            description: String::from("Gets the local device address."),
            function_pointer: CommandHandler::cmd_get_address,
        },
    );
    command_options.insert(
        String::from("help"),
        CommandOption {
            rules: vec![String::from("help")],
            description: String::from("Shows this menu."),
            function_pointer: CommandHandler::cmd_help,
        },
    );
    command_options.insert(
        String::from("list"),
        CommandOption {
            rules: vec![String::from("list <bonded|found|connected>")],
            description: String::from(
                "List bonded or found remote devices. Use: list <bonded|found>",
            ),
            function_pointer: CommandHandler::cmd_list_devices,
        },
    );
    command_options.insert(
        String::from("quit"),
        CommandOption {
            rules: vec![String::from("quit")],
            description: String::from("Quit out of the interactive shell."),
            function_pointer: _noop,
        },
    );
    command_options
}

// Helper to index a vector safely. The same as `args.get(i)` but converts the None into a
// CommandError::InvalidArgs.
//
// Use this to safely index an argument and conveniently return the error if the argument does not
// exist.
fn get_arg<I>(
    args: &Vec<String>,
    index: I,
) -> Result<&<I as SliceIndex<[String]>>::Output, CommandError>
where
    I: SliceIndex<[String]>,
{
    args.get(index).ok_or(CommandError::InvalidArgs)
}

impl CommandHandler {
    /// Creates a new CommandHandler.
    pub fn new(context: Arc<Mutex<ClientContext>>) -> CommandHandler {
        CommandHandler { context, command_options: build_commands() }
    }

    /// Entry point for command and arguments
    pub fn process_cmd_line(&mut self, command: &String, args: &Vec<String>) {
        // Ignore empty line
        match &command[..] {
            "" => {}
            _ => match self.command_options.get(command) {
                Some(cmd) => {
                    let rules = cmd.rules.clone();
                    match (cmd.function_pointer)(self, &args) {
                        Ok(()) => {}
                        Err(CommandError::InvalidArgs) => {
                            print_error!("Invalid arguments. Usage:\n{}", rules.join("\n"));
                        }
                        Err(CommandError::Failed(msg)) => {
                            print_error!("Command failed: {}", msg);
                        }
                    }
                }
                None => {
                    println!("'{}' is an invalid command!", command);
                    self.cmd_help(&args).ok();
                }
            },
        };
    }

    // Common message for when the adapter isn't ready
    fn adapter_not_ready(&self) -> CommandError {
        CommandError::Failed(format!(
            "Default adapter {} is not enabled. Enable the adapter before using this command.",
            self.context.lock().unwrap().default_adapter
        ))
    }

    fn cmd_help(&mut self, args: &Vec<String>) -> CommandResult {
        if let Some(command) = args.get(0) {
            match self.command_options.get(command) {
                Some(cmd) => {
                    println!(
                        "\n{}{}\n{}{}\n",
                        INDENT_CHAR.repeat(4),
                        command,
                        INDENT_CHAR.repeat(8),
                        cmd.description
                    );
                }
                None => {
                    println!("'{}' is an invalid command!", command);
                    self.cmd_help(&vec![]).ok();
                }
            }
        } else {
            // Build equals bar and Shave off sides
            let equal_bar = format!(" {} ", BAR1_CHAR.repeat(MAX_MENU_CHAR_WIDTH));

            // Build empty bar and Shave off sides
            let empty_bar = format!("|{}|", INDENT_CHAR.repeat(MAX_MENU_CHAR_WIDTH));

            // Header
            println!(
                "\n{}\n{}\n{}\n{}",
                equal_bar,
                wrap_help_text("Help Menu", MAX_MENU_CHAR_WIDTH, 2),
                // Minus bar
                format!("+{}+", BAR2_CHAR.repeat(MAX_MENU_CHAR_WIDTH)),
                empty_bar
            );

            // Print commands
            for (key, val) in self.command_options.iter() {
                println!(
                    "{}\n{}\n{}",
                    wrap_help_text(&key, MAX_MENU_CHAR_WIDTH, 4),
                    wrap_help_text(&val.description, MAX_MENU_CHAR_WIDTH, 8),
                    empty_bar
                );
            }

            // Footer
            println!("{}\n{}", empty_bar, equal_bar);
        }

        Ok(())
    }

    fn cmd_adapter(&mut self, args: &Vec<String>) -> CommandResult {
        if !self.context.lock().unwrap().manager_dbus.get_floss_enabled() {
            return Err(CommandError::Failed(
                "Floss is not enabled. First run, `floss enable`".into(),
            ));
        }

        let default_adapter = self.context.lock().unwrap().default_adapter;

        let command = get_arg(args, 0)?;

        match &command[..] {
            "enable" => {
                if self.context.lock().unwrap().is_restricted {
                    return Err(CommandError::Failed(
                        "You are not allowed to toggle adapter power".into(),
                    ));
                }
                self.context.lock().unwrap().manager_dbus.start(default_adapter);
            }
            "disable" => {
                if self.context.lock().unwrap().is_restricted {
                    return Err(CommandError::Failed(
                        "You are not allowed to toggle adapter power".into(),
                    ));
                }
                self.context.lock().unwrap().manager_dbus.stop(default_adapter);
            }
            "show" => {
                if !self.context.lock().unwrap().adapter_ready {
                    return Err(self.adapter_not_ready());
                }

                let enabled = self.context.lock().unwrap().enabled;
                let address = match self.context.lock().unwrap().adapter_address.as_ref() {
                    Some(x) => x.clone(),
                    None => String::from(""),
                };
                let context = self.context.lock().unwrap();
                let adapter_dbus = context.adapter_dbus.as_ref().unwrap();
                let qa_dbus = context.qa_dbus.as_ref().unwrap();
                let name = adapter_dbus.get_name();
                let uuids = adapter_dbus.get_uuids();
                let is_discoverable = adapter_dbus.get_discoverable();
                let is_connectable = qa_dbus.get_connectable();
                let discoverable_timeout = adapter_dbus.get_discoverable_timeout();
                let cod = adapter_dbus.get_bluetooth_class();
                let multi_adv_supported = adapter_dbus.is_multi_advertisement_supported();
                let le_ext_adv_supported = adapter_dbus.is_le_extended_advertising_supported();
                let wbs_supported = adapter_dbus.is_wbs_supported();
                let supported_profiles = UuidHelper::get_supported_profiles();
                let connected_profiles: Vec<Profile> = supported_profiles
                    .iter()
                    .filter(|&&prof| adapter_dbus.get_profile_connection_state(prof) > 0)
                    .cloned()
                    .collect();
                print_info!("Address: {}", address);
                print_info!("Name: {}", name);
                print_info!("State: {}", if enabled { "enabled" } else { "disabled" });
                print_info!("Discoverable: {}", is_discoverable);
                print_info!("DiscoverableTimeout: {}s", discoverable_timeout);
                print_info!("Connectable: {}", is_connectable);
                print_info!("Class: {:#06x}", cod);
                print_info!("IsMultiAdvertisementSupported: {}", multi_adv_supported);
                print_info!("IsLeExtendedAdvertisingSupported: {}", le_ext_adv_supported);
                print_info!("Connected profiles: {:?}", connected_profiles);
                print_info!("IsWbsSupported: {}", wbs_supported);
                print_info!(
                    "Uuids: {}",
                    DisplayList(
                        uuids
                            .iter()
                            .map(|&x| UuidHelper::known_uuid_to_string(&x))
                            .collect::<Vec<String>>()
                    )
                );
            }
            "discoverable" => match &get_arg(args, 1)?[..] {
                "on" => {
                    let discoverable = self
                        .context
                        .lock()
                        .unwrap()
                        .adapter_dbus
                        .as_mut()
                        .unwrap()
                        .set_discoverable(true, 60);
                    print_info!(
                        "Set discoverable for 60s: {}",
                        if discoverable { "succeeded" } else { "failed" }
                    );
                }
                "off" => {
                    let discoverable = self
                        .context
                        .lock()
                        .unwrap()
                        .adapter_dbus
                        .as_mut()
                        .unwrap()
                        .set_discoverable(false, 60);
                    print_info!(
                        "Turn discoverable off: {}",
                        if discoverable { "succeeded" } else { "failed" }
                    );
                }
                other => println!("Invalid argument for adapter discoverable '{}'", other),
            },
            "connectable" => match &get_arg(args, 1)?[..] {
                "on" => {
                    let ret = self
                        .context
                        .lock()
                        .unwrap()
                        .qa_dbus
                        .as_mut()
                        .unwrap()
                        .set_connectable(true);
                    print_info!("Set connectable on {}", if ret { "succeeded" } else { "failed" });
                }
                "off" => {
                    let ret = self
                        .context
                        .lock()
                        .unwrap()
                        .qa_dbus
                        .as_mut()
                        .unwrap()
                        .set_connectable(false);
                    print_info!("Set connectable off {}", if ret { "succeeded" } else { "failed" });
                }
                other => println!("Invalid argument for adapter connectable '{}'", other),
            },
            "set-name" => {
                if let Some(name) = args.get(1) {
                    self.context
                        .lock()
                        .unwrap()
                        .adapter_dbus
                        .as_ref()
                        .unwrap()
                        .set_name(name.to_string());
                } else {
                    println!("usage: adapter set-name <name>");
                }
            }

            _ => return Err(CommandError::InvalidArgs),
        };

        Ok(())
    }

    fn cmd_get_address(&mut self, _args: &Vec<String>) -> CommandResult {
        if !self.context.lock().unwrap().adapter_ready {
            return Err(self.adapter_not_ready());
        }

        let address = self.context.lock().unwrap().update_adapter_address();
        print_info!("Local address = {}", &address);
        Ok(())
    }

    fn cmd_discovery(&mut self, args: &Vec<String>) -> CommandResult {
        if !self.context.lock().unwrap().adapter_ready {
            return Err(self.adapter_not_ready());
        }

        let command = get_arg(args, 0)?;

        match &command[..] {
            "start" => {
                self.context.lock().unwrap().adapter_dbus.as_ref().unwrap().start_discovery();
            }
            "stop" => {
                self.context.lock().unwrap().adapter_dbus.as_ref().unwrap().cancel_discovery();
            }
            _ => return Err(CommandError::InvalidArgs),
        }

        Ok(())
    }

    fn cmd_bond(&mut self, args: &Vec<String>) -> CommandResult {
        if !self.context.lock().unwrap().adapter_ready {
            return Err(self.adapter_not_ready());
        }

        let command = get_arg(args, 0)?;

        match &command[..] {
            "add" => {
                let device = BluetoothDevice {
                    address: String::from(get_arg(args, 1)?),
                    name: String::from("Classic Device"),
                };

                let bonding_attempt =
                    &self.context.lock().unwrap().bonding_attempt.as_ref().cloned();

                if bonding_attempt.is_some() {
                    return Err(CommandError::Failed(
                        format!(
                            "Already bonding [{}]. Cancel bonding first.",
                            bonding_attempt.as_ref().unwrap().address,
                        )
                        .into(),
                    ));
                }

                let success = self
                    .context
                    .lock()
                    .unwrap()
                    .adapter_dbus
                    .as_ref()
                    .unwrap()
                    .create_bond(device.clone(), BtTransport::Auto);

                if success {
                    self.context.lock().unwrap().bonding_attempt = Some(device);
                }
            }
            "remove" => {
                let device = BluetoothDevice {
                    address: String::from(get_arg(args, 1)?),
                    name: String::from("Classic Device"),
                };

                self.context.lock().unwrap().adapter_dbus.as_ref().unwrap().remove_bond(device);
            }
            "cancel" => {
                let device = BluetoothDevice {
                    address: String::from(get_arg(args, 1)?),
                    name: String::from("Classic Device"),
                };

                self.context
                    .lock()
                    .unwrap()
                    .adapter_dbus
                    .as_ref()
                    .unwrap()
                    .cancel_bond_process(device);
            }
            other => {
                println!("Invalid argument '{}'", other);
            }
        }

        Ok(())
    }

    fn cmd_device(&mut self, args: &Vec<String>) -> CommandResult {
        if !self.context.lock().unwrap().adapter_ready {
            return Err(self.adapter_not_ready());
        }

        let command = &get_arg(args, 0)?;

        match &command[..] {
            "connect" => {
                let device = BluetoothDevice {
                    address: String::from(get_arg(args, 1)?),
                    name: String::from("Classic Device"),
                };

                let success = self
                    .context
                    .lock()
                    .unwrap()
                    .adapter_dbus
                    .as_mut()
                    .unwrap()
                    .connect_all_enabled_profiles(device.clone());

                if success {
                    println!("Connecting to {}", &device.address);
                } else {
                    println!("Can't connect to {}", &device.address);
                }
            }
            "disconnect" => {
                let device = BluetoothDevice {
                    address: String::from(get_arg(args, 1)?),
                    name: String::from("Classic Device"),
                };

                let success = self
                    .context
                    .lock()
                    .unwrap()
                    .adapter_dbus
                    .as_mut()
                    .unwrap()
                    .disconnect_all_enabled_profiles(device.clone());

                if success {
                    println!("Disconnecting from {}", &device.address);
                } else {
                    println!("Can't disconnect from {}", &device.address);
                }
            }
            "info" => {
                let device = BluetoothDevice {
                    address: String::from(get_arg(args, 1)?),
                    name: String::from("Classic Device"),
                };

                let (
                    name,
                    alias,
                    device_type,
                    class,
                    appearance,
                    bonded,
                    connection_state,
                    uuids,
                    wake_allowed,
                ) = {
                    let ctx = self.context.lock().unwrap();
                    let adapter = ctx.adapter_dbus.as_ref().unwrap();

                    let name = adapter.get_remote_name(device.clone());
                    let device_type = adapter.get_remote_type(device.clone());
                    let alias = adapter.get_remote_alias(device.clone());
                    let class = adapter.get_remote_class(device.clone());
                    let appearance = adapter.get_remote_appearance(device.clone());
                    let bonded = adapter.get_bond_state(device.clone());
                    let connection_state = match adapter.get_connection_state(device.clone()) {
                        BtConnectionState::NotConnected => "Not Connected",
                        BtConnectionState::ConnectedOnly => "Connected",
                        _ => "Connected and Paired",
                    };
                    let uuids = adapter.get_remote_uuids(device.clone());
                    let wake_allowed = adapter.get_remote_wake_allowed(device.clone());

                    (
                        name,
                        alias,
                        device_type,
                        class,
                        appearance,
                        bonded,
                        connection_state,
                        uuids,
                        wake_allowed,
                    )
                };

                print_info!("Address: {}", &device.address);
                print_info!("Name: {}", name);
                print_info!("Alias: {}", alias);
                print_info!("Type: {:?}", device_type);
                print_info!("Class: {}", class);
                print_info!("Appearance: {}", appearance);
                print_info!("Wake Allowed: {}", wake_allowed);
                print_info!("Bond State: {:?}", bonded);
                print_info!("Connection State: {}", connection_state);
                print_info!(
                    "Uuids: {}",
                    DisplayList(
                        uuids
                            .iter()
                            .map(|&x| UuidHelper::known_uuid_to_string(&x))
                            .collect::<Vec<String>>()
                    )
                );
            }
            "set-alias" => {
                let new_alias = get_arg(args, 2)?;
                let device = BluetoothDevice {
                    address: String::from(get_arg(args, 1)?),
                    name: String::from(""),
                };
                let old_alias = self
                    .context
                    .lock()
                    .unwrap()
                    .adapter_dbus
                    .as_ref()
                    .unwrap()
                    .get_remote_alias(device.clone());
                println!(
                    "Updating alias for {}: {} -> {}",
                    get_arg(args, 1)?,
                    old_alias,
                    new_alias
                );
                self.context
                    .lock()
                    .unwrap()
                    .adapter_dbus
                    .as_mut()
                    .unwrap()
                    .set_remote_alias(device.clone(), new_alias.clone());
            }
            "set-pairing-confirmation" => {
                let device = BluetoothDevice {
                    address: String::from(get_arg(args, 1)?),
                    name: String::from(""),
                };
                let accept = match &get_arg(args, 2)?[..] {
                    "accept" => true,
                    "reject" => false,
                    other => {
                        return Err(CommandError::Failed(format!("Failed to parse '{}'", other)));
                    }
                };

                self.context
                    .lock()
                    .unwrap()
                    .adapter_dbus
                    .as_mut()
                    .unwrap()
                    .set_pairing_confirmation(device.clone(), accept);
            }
            "set-pairing-pin" => {
                let device = BluetoothDevice {
                    address: String::from(get_arg(args, 1)?),
                    name: String::from(""),
                };
                let pin = get_arg(args, 2)?;
                let (accept, pin) = match (&pin[..], String::from(pin).parse::<u32>()) {
                    (_, Ok(p)) => (true, Vec::from(p.to_ne_bytes())),
                    ("reject", _) => (false, vec![]),
                    _ => {
                        return Err(CommandError::Failed(format!("Failed to parse '{}'", pin)));
                    }
                };

                self.context.lock().unwrap().adapter_dbus.as_mut().unwrap().set_pin(
                    device.clone(),
                    accept,
                    pin,
                );
            }
            "set-pairing-passkey" => {
                let device = BluetoothDevice {
                    address: String::from(get_arg(args, 1)?),
                    name: String::from(""),
                };
                let passkey = get_arg(args, 2)?;
                let (accept, passkey) = match (&passkey[..], String::from(passkey).parse::<u32>()) {
                    (_, Ok(p)) => (true, Vec::from(p.to_ne_bytes())),
                    ("reject", _) => (false, vec![]),
                    _ => {
                        return Err(CommandError::Failed(format!("Failed to parse '{}'", passkey)));
                    }
                };

                self.context.lock().unwrap().adapter_dbus.as_mut().unwrap().set_passkey(
                    device.clone(),
                    accept,
                    passkey,
                );
            }
            other => {
                println!("Invalid argument '{}'", other);
            }
        }

        Ok(())
    }

    fn cmd_floss(&mut self, args: &Vec<String>) -> CommandResult {
        let command = get_arg(args, 0)?;

        match &command[..] {
            "enable" => {
                self.context.lock().unwrap().manager_dbus.set_floss_enabled(true);
            }
            "disable" => {
                self.context.lock().unwrap().manager_dbus.set_floss_enabled(false);
            }
            "show" => {
                print_info!(
                    "Floss enabled: {}",
                    self.context.lock().unwrap().manager_dbus.get_floss_enabled()
                );
            }
            _ => return Err(CommandError::InvalidArgs),
        }

        Ok(())
    }

    fn cmd_gatt(&mut self, args: &Vec<String>) -> CommandResult {
        if !self.context.lock().unwrap().adapter_ready {
            return Err(self.adapter_not_ready());
        }

        let command = get_arg(args, 0)?;

        match &command[..] {
            "register-client" => {
                let dbus_connection = self.context.lock().unwrap().dbus_connection.clone();
                let dbus_crossroads = self.context.lock().unwrap().dbus_crossroads.clone();

                self.context.lock().unwrap().gatt_dbus.as_mut().unwrap().register_client(
                    String::from(GATT_CLIENT_APP_UUID),
                    Box::new(BtGattCallback::new(
                        String::from("/org/chromium/bluetooth/client/bluetooth_gatt_callback"),
                        self.context.clone(),
                        dbus_connection,
                        dbus_crossroads,
                    )),
                    false,
                );
            }
            "client-connect" => {
                let client_id = self.context.lock().unwrap().gatt_client_id;
                if client_id.is_none() {
                    return Err(CommandError::Failed(format!(
                        "GATT client is not yet registered."
                    )));
                }

                let addr = String::from(get_arg(args, 1)?);
                self.context.lock().unwrap().gatt_dbus.as_ref().unwrap().client_connect(
                    client_id.unwrap(),
                    addr,
                    false,
                    BtTransport::Le,
                    false,
                    LePhy::Phy1m,
                );
            }
            "client-disconnect" => {
                let client_id = self.context.lock().unwrap().gatt_client_id;
                if client_id.is_none() {
                    return Err(CommandError::Failed(format!(
                        "GATT client is not yet registered."
                    )));
                }

                let addr = String::from(get_arg(args, 1)?);
                self.context
                    .lock()
                    .unwrap()
                    .gatt_dbus
                    .as_ref()
                    .unwrap()
                    .client_disconnect(client_id.unwrap(), addr);
            }
            "client-read-phy" => {
                let client_id = self.context.lock().unwrap().gatt_client_id;
                if client_id.is_none() {
                    return Err(CommandError::Failed(format!(
                        "GATT client is not yet registered."
                    )));
                }

                let addr = String::from(get_arg(args, 1)?);
                self.context
                    .lock()
                    .unwrap()
                    .gatt_dbus
                    .as_mut()
                    .unwrap()
                    .client_read_phy(client_id.unwrap(), addr);
            }
            "client-discover-services" => {
                let client_id = self.context.lock().unwrap().gatt_client_id;
                if client_id.is_none() {
                    return Err(CommandError::Failed(format!(
                        "GATT client is not yet registered."
                    )));
                }

                let addr = String::from(get_arg(args, 1)?);
                self.context
                    .lock()
                    .unwrap()
                    .gatt_dbus
                    .as_ref()
                    .unwrap()
                    .discover_services(client_id.unwrap(), addr);
            }
            "configure-mtu" => {
                let client_id = self.context.lock().unwrap().gatt_client_id;
                if client_id.is_none() {
                    return Err(CommandError::Failed(format!(
                        "GATT client is not yet registered."
                    )));
                }

                let addr = String::from(get_arg(args, 1)?);
                let mtu = String::from(get_arg(args, 2)?).parse::<i32>();
                if let Ok(m) = mtu {
                    self.context.lock().unwrap().gatt_dbus.as_ref().unwrap().configure_mtu(
                        client_id.unwrap(),
                        addr,
                        m,
                    );
                } else {
                    return Err(CommandError::Failed(format!("Failed parsing mtu")));
                }
            }
            _ => return Err(CommandError::InvalidArgs),
        }

        Ok(())
    }

    fn cmd_le_scan(&mut self, args: &Vec<String>) -> CommandResult {
        if !self.context.lock().unwrap().adapter_ready {
            return Err(self.adapter_not_ready());
        }

        let command = get_arg(args, 0)?;

        match &command[..] {
            "register-scanner" => {
                let scanner_callback_id = self.context.lock().unwrap().scanner_callback_id;
                if let Some(id) = scanner_callback_id {
                    let uuid = self
                        .context
                        .lock()
                        .unwrap()
                        .gatt_dbus
                        .as_mut()
                        .unwrap()
                        .register_scanner(id);
                    print_info!("Scanner to be registered with UUID = {}", UuidWrapper(&uuid));
                } else {
                    print_error!("Cannot register scanner before registering scanner callback");
                }
            }
            "unregister-scanner" => {
                let scanner_id = String::from(get_arg(args, 1)?).parse::<u8>();

                if let Ok(id) = scanner_id {
                    self.context.lock().unwrap().gatt_dbus.as_mut().unwrap().unregister_scanner(id);
                } else {
                    return Err(CommandError::Failed(format!("Failed parsing scanner id")));
                }
            }
            "start-scan" => {
                let scanner_id = String::from(get_arg(args, 1)?).parse::<u8>();

                if let Ok(id) = scanner_id {
                    self.context.lock().unwrap().gatt_dbus.as_mut().unwrap().start_scan(
                        id,
                        // TODO(b/217274432): Construct real settings and filters.
                        ScanSettings { interval: 0, window: 0, scan_type: ScanType::Active },
                        None,
                    );
                    self.context.lock().unwrap().active_scanner_ids.insert(id);
                } else {
                    return Err(CommandError::Failed(format!("Failed parsing scanner id")));
                }
            }
            "stop-scan" => {
                let scanner_id = String::from(get_arg(args, 1)?).parse::<u8>();

                if let Ok(id) = scanner_id {
                    self.context.lock().unwrap().gatt_dbus.as_mut().unwrap().stop_scan(id);
                    self.context.lock().unwrap().active_scanner_ids.remove(&id);
                } else {
                    return Err(CommandError::Failed(format!("Failed parsing scanner id")));
                }
            }
            _ => return Err(CommandError::InvalidArgs),
        }

        Ok(())
    }

    // TODO(b/233128828): More options will be implemented to test BLE advertising.
    // Such as setting advertising parameters, starting multiple advertising sets, etc.
    fn cmd_advertise(&mut self, args: &Vec<String>) -> CommandResult {
        if !self.context.lock().unwrap().adapter_ready {
            return Err(self.adapter_not_ready());
        }

        if self.context.lock().unwrap().advertiser_callback_id == None {
            return Err(CommandError::Failed("No advertiser callback registered".into()));
        }

        let callback_id = self.context.lock().unwrap().advertiser_callback_id.clone().unwrap();

        let command = get_arg(args, 0)?;

        match &command[..] {
            "on" => {
                print_info!("Creating legacy advertising set...");
                let s = AdvSet::new(true); // legacy advertising
                AdvSet::start(self.context.clone(), s, callback_id);
            }
            "off" => {
                AdvSet::stop_all(self.context.clone());
            }
            "ext" => {
                print_info!("Creating extended advertising set...");
                let s = AdvSet::new(false); // extended advertising
                AdvSet::start(self.context.clone(), s, callback_id);
            }
            "set-interval" => {
                let ms = String::from(get_arg(args, 1)?).parse::<i32>();
                if !ms.is_ok() {
                    return Err(CommandError::Failed(format!("Failed parsing interval")));
                }
                let interval = ms.unwrap() * 8 / 5; // in 0.625 ms.

                let mut context = self.context.lock().unwrap();
                context.adv_sets.iter_mut().for_each(|(_, s)| s.params.interval = interval);

                // To avoid borrowing context as mutable from an immutable borrow.
                // Required information is collected in advance and then passed
                // to the D-Bus call which requires a mutable borrow.
                let advs: Vec<(_, _)> = context
                    .adv_sets
                    .iter()
                    .filter_map(|(_, s)| s.adv_id.map(|adv_id| (adv_id.clone(), s.params.clone())))
                    .collect();
                for (adv_id, params) in advs {
                    print_info!("Setting advertising parameters for {}", adv_id);
                    context.gatt_dbus.as_mut().unwrap().set_advertising_parameters(adv_id, params);
                }
            }
            "set-scan-rsp" => {
                let enable = match &get_arg(args, 1)?[..] {
                    "enable" => true,
                    "disable" => false,
                    _ => false,
                };

                let mut context = self.context.lock().unwrap();
                context.adv_sets.iter_mut().for_each(|(_, s)| s.params.scannable = enable);

                let advs: Vec<(_, _, _)> = context
                    .adv_sets
                    .iter()
                    .filter_map(|(_, s)| {
                        s.adv_id
                            .map(|adv_id| (adv_id.clone(), s.params.clone(), s.scan_rsp.clone()))
                    })
                    .collect();
                for (adv_id, params, scan_rsp) in advs {
                    print_info!("Setting scan response data for {}", adv_id);
                    context.gatt_dbus.as_mut().unwrap().set_scan_response_data(adv_id, scan_rsp);
                    print_info!("Setting parameters for {}", adv_id);
                    context.gatt_dbus.as_mut().unwrap().set_advertising_parameters(adv_id, params);
                }
            }
            _ => return Err(CommandError::InvalidArgs),
        }

        Ok(())
    }

    fn cmd_socket(&mut self, args: &Vec<String>) -> CommandResult {
        if !self.context.lock().unwrap().adapter_ready {
            return Err(self.adapter_not_ready());
        }

        let callback_id = match self.context.lock().unwrap().socket_manager_callback_id.clone() {
            Some(id) => id,
            None => {
                return Err(CommandError::Failed("No socket manager callback registered.".into()));
            }
        };

        let command = get_arg(args, 0)?;

        match &command[..] {
            "test" => {
                let SocketResult { status, id } = self
                    .context
                    .lock()
                    .unwrap()
                    .socket_manager_dbus
                    .as_mut()
                    .unwrap()
                    .listen_using_l2cap_channel(callback_id);

                if status != BtStatus::Success {
                    return Err(CommandError::Failed(format!(
                        "Failed to request for listening using l2cap channel, status = {:?}",
                        status,
                    )));
                }
                print_info!("Requested for listening using l2cap channel on socket {}", id);
            }
            _ => return Err(CommandError::InvalidArgs),
        }

        Ok(())
    }

    /// Get the list of rules of supported commands
    pub fn get_command_rule_list(&self) -> Vec<String> {
        self.command_options.values().flat_map(|cmd| cmd.rules.clone()).collect()
    }

    fn cmd_list_devices(&mut self, args: &Vec<String>) -> CommandResult {
        if !self.context.lock().unwrap().adapter_ready {
            return Err(self.adapter_not_ready());
        }

        let command = get_arg(args, 1)?;

        match &command[..] {
            "bonded" => {
                print_info!("Known bonded devices:");
                let devices = self
                    .context
                    .lock()
                    .unwrap()
                    .adapter_dbus
                    .as_ref()
                    .unwrap()
                    .get_bonded_devices();
                for device in devices.iter() {
                    print_info!("[{:17}] {}", device.address, device.name);
                }
            }
            "found" => {
                print_info!("Devices found in most recent discovery session:");
                for (key, val) in self.context.lock().unwrap().found_devices.iter() {
                    print_info!("[{:17}] {}", key, val.name);
                }
            }
            "connected" => {
                print_info!("Connected devices:");
                let devices = self
                    .context
                    .lock()
                    .unwrap()
                    .adapter_dbus
                    .as_ref()
                    .unwrap()
                    .get_connected_devices();
                for device in devices.iter() {
                    print_info!("[{:17}] {}", device.address, device.name);
                }
            }
            other => {
                println!("Invalid argument '{}'", other);
            }
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {

    use super::*;

    #[test]
    fn test_wrap_help_text() {
        let text = "hello";
        let text_len = text.chars().count();
        // ensure no overflow
        assert_eq!(format!("|{}|", text), wrap_help_text(text, 4, 0));
        assert_eq!(format!("|{}|", text), wrap_help_text(text, 5, 0));
        assert_eq!(format!("|{}{}|", text, " "), wrap_help_text(text, 6, 0));
        assert_eq!(format!("|{}{}|", text, " ".repeat(2)), wrap_help_text(text, 7, 0));
        assert_eq!(
            format!("|{}{}|", text, " ".repeat(100 - text_len)),
            wrap_help_text(text, 100, 0)
        );
        assert_eq!(format!("|{}{}|", " ", text), wrap_help_text(text, 4, 1));
        assert_eq!(format!("|{}{}|", " ".repeat(2), text), wrap_help_text(text, 5, 2));
        assert_eq!(format!("|{}{}{}|", " ".repeat(3), text, " "), wrap_help_text(text, 6, 3));
        assert_eq!(
            format!("|{}{}{}|", " ".repeat(4), text, " ".repeat(7 - text_len)),
            wrap_help_text(text, 7, 4)
        );
        assert_eq!(format!("|{}{}|", " ".repeat(9), text), wrap_help_text(text, 4, 9));
        assert_eq!(format!("|{}{}|", " ".repeat(10), text), wrap_help_text(text, 3, 10));
        assert_eq!(format!("|{}{}|", " ".repeat(11), text), wrap_help_text(text, 2, 11));
        assert_eq!(format!("|{}{}|", " ".repeat(12), text), wrap_help_text(text, 1, 12));
        assert_eq!("||", wrap_help_text("", 0, 0));
        assert_eq!("| |", wrap_help_text("", 1, 0));
        assert_eq!("|  |", wrap_help_text("", 1, 1));
        assert_eq!("| |", wrap_help_text("", 0, 1));
    }
}
