fn main() {
    uniffi::generate_scaffolding("src/alphamessenger.udl").expect("Failed to generate UniFFI scaffolding");
}
