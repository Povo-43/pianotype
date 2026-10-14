mod config;
mod engine;
mod gui;
mod keymap;
mod midi;

use eframe::egui;

fn main() -> eframe::Result<()> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    let native_options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([760.0, 680.0])
            .with_min_inner_size([540.0, 460.0])
            .with_title("PianoType PC"),
        ..Default::default()
    };

    eframe::run_native(
        "PianoType PC",
        native_options,
        Box::new(|cc| Box::new(gui::PianotypeApp::new(cc))),
    )
}
