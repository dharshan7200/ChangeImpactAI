package com.changeimpact.parser.language.ts;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.parser.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static com.changeimpact.parser.language.ts.TreeSitterFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RubyLanguageParserTest {

    @TempDir Path root;
    private RubyLanguageParser parser;

    @BeforeEach
    void setUp() {
        parser = new RubyLanguageParser(new AppProperties());
        assumeTrue(parser.available(), "tree-sitter native library not available on this platform");
    }

    private void railsApp() throws Exception {
        write(root, "app/models/application_record.rb", """
                class ApplicationRecord < ActiveRecord::Base
                  self.abstract_class = true
                end
                """);
        write(root, "app/models/concerns/payable.rb", """
                module Payable
                  def pay!(amount)
                    amount
                  end
                end
                """);
        write(root, "app/models/line_item.rb", "class LineItem < ApplicationRecord\n  belongs_to :invoice\nend\n");
        write(root, "app/models/billing/invoice.rb", """
                module Billing
                  class Invoice < ApplicationRecord
                    include Payable
                    has_many :line_items
                    attr_reader :total

                    def initialize(total)
                      @total = total
                      @gateway = Gateway.new
                    end

                    def charge!(card)
                      @gateway.charge(card, total)
                      self.notify
                      notify
                      pay!(total)
                      Receipt.new(self).deliver
                      if card && total > 0
                        Gateway.ping
                      end
                    end

                    def self.build(x)
                      new(x)
                    end

                    private

                    def notify
                      items = line_items.map { |li| li.price }
                    end
                  end

                  class Gateway
                    def charge(card, amount); end
                    def self.ping; end
                  end

                  class Receipt
                    def initialize(invoice); @invoice = invoice; end
                    def deliver
                      "INSERT INTO receipts (invoice_id) VALUES (1)"
                    end
                  end
                end
                """);
        write(root, "lib/money/format.rb", "def format_money(x)\n  x.to_s\nend\n");
        write(root, "app/services/checkout.rb", """
                require 'json'
                require 'money/format'
                require_relative '../models/billing/invoice'

                class Checkout < Base
                  def run
                    invoice = Billing::Invoice.build(10)
                    invoice.charge!(nil)
                    other = Billing::Invoice.new(5)
                    other.charge!(:visa)
                    unknown.charge!(1)
                  end
                end
                """);
        write(root, "db/schema.rb", """
                ActiveRecord::Schema[7.1].define(version: 2024_01_01) do
                  create_table "invoices", force: :cascade do |t|
                    t.decimal "total"
                    t.references :customer
                  end
                end
                """);
        write(root, "db/migrate/20240101_create_invoices.rb", """
                class CreateInvoices < ActiveRecord::Migration[7.1]
                  def change
                    create_table :invoices do |t|
                      t.decimal :total
                    end
                  end
                end
                """);
        write(root, "spec/models/invoice_spec.rb", """
                require 'rails_helper'
                RSpec.describe Billing::Invoice do
                  it 'charges' do
                    Billing::Invoice.new(1).charge!(nil)
                  end
                end
                """);
    }

    @Test
    void railsProject() throws Exception {
        railsApp();
        Map<String, ParsedSourceFile> files = parseAll(parser, root);
        assertThat(files.get("spec/models/invoice_spec.rb").isTest()).isTrue();

        var symbols = symbols(files);
        assertThat(symbols).containsKeys("Billing", "Billing.Invoice", "Billing.Invoice#charge!", "Billing.Invoice#total",
                "Billing.Invoice#line_items", "Billing.Gateway#ping", "Payable#pay!", "lib.money.format.rb#format_money",
                "app.services.checkout.rb", "db:invoices", "db:invoices#total", "db:invoices#customer_id");
        assertThat(symbols.get("Billing").getSymbolType()).isEqualTo(SymbolType.MODULE);
        assertThat(symbols.get("Billing.Invoice#initialize").getSymbolType()).isEqualTo(SymbolType.CONSTRUCTOR);
        assertThat(symbols.get("Billing.Invoice#build").isStatic()).isTrue();
        assertThat(symbols.get("Billing.Invoice#notify").getVisibility()).isEqualTo("private");
        assertThat(symbols.get("Billing.Invoice").getAnnotations()).contains("model");
        assertThat(symbols.get("Billing.Invoice#charge!").getComplexity()).isEqualTo(3);
        // the schema defines the table; the migration does not redefine it
        assertThat(files.get("db/migrate/20240101_create_invoices.rb").getSymbols()).noneMatch(x -> x.getQualifiedName().startsWith("db:"));

        assertThat(deps(files, DependencyType.EXTENDS)).contains("Billing.Invoice -> ApplicationRecord", "Billing.Invoice -> Payable",
                "ApplicationRecord -> ActiveRecord.Base");
        assertThat(deps(files, DependencyType.IMPORT)).contains("app.services.checkout.rb -> gem:json",
                "app.services.checkout.rb -> lib.money.format.rb", "app.services.checkout.rb -> app.models.billing.invoice.rb");
        assertThat(deps(files, DependencyType.ANNOTATION)).contains("Billing.Invoice -> db:invoices", "LineItem -> db:line_items");
        assertThat(deps(files, DependencyType.ANNOTATION)).noneMatch(d -> d.startsWith("ApplicationRecord"));
        assertThat(deps(files, DependencyType.FIELD_TYPE)).contains("Billing.Invoice#line_items -> LineItem",
                "db:invoices -> db:customers");

        String charge = "Billing.Invoice#charge!";
        assertThat(deps(files, DependencyType.METHOD_CALL)).contains(
                charge + " -> Billing.Gateway#charge",     // @gateway = Gateway.new
                charge + " -> Billing.Invoice#notify",     // self.notify and bare notify
                charge + " -> Payable#pay!",               // included module
                charge + " -> Billing.Receipt#deliver",    // Receipt.new(self).deliver
                charge + " -> Billing.Gateway#ping",       // class method
                "Checkout#run -> Billing.Invoice#build",
                "Checkout#run -> Billing.Invoice#charge!",
                "spec.models.invoice_spec.rb -> Billing.Invoice#charge!");
        assertThat(deps(files, DependencyType.METHOD_CALL)).noneMatch(d -> d.contains("unknown"));
        assertThat(deps(files, DependencyType.CONSTRUCTOR_CALL)).contains(charge + " -> Billing.Receipt",
                "Billing.Invoice#initialize -> Billing.Gateway", "Checkout#run -> Billing.Invoice");
        assertThat(deps(files, DependencyType.FIELD_ACCESS)).contains("Billing.Invoice#initialize -> Billing.Invoice#total",
                "Billing.Receipt#deliver -> db:receipts");
    }
}
