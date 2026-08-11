package com.rpa.financial_intelligence_system.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 企业文档文本抽取器。分页文档保留页码，表格文档转换为 Markdown 表格。 */
@Service
public class KnowledgeParserService {
    public record ParsedPage(Integer pageNumber, String text) {}

    private final StorageService storage;

    public KnowledgeParserService(StorageService storage) {
        this.storage = storage;
    }

    public List<ParsedPage> parse(Map<String, Object> file) throws Exception {
        String name = String.valueOf(file.get("original_name"));
        String extension = extension(name);
        Resource resource = storage.download(file);
        try (InputStream input = resource.getInputStream()) {
            return switch (extension) {
                case "pdf" -> parsePdf(input);
                case "docx" -> parseDocx(input);
                case "doc" -> parseDoc(input);
                case "xlsx", "xls", "xlsm" -> parseExcel(input);
                case "md", "markdown", "txt", "csv", "json", "xml", "html" ->
                        List.of(new ParsedPage(1, new String(input.readAllBytes(), StandardCharsets.UTF_8)));
                default -> throw new IllegalArgumentException("暂不支持将 ." + extension + " 文件解析为知识库文档");
            };
        }
    }

    private List<ParsedPage> parsePdf(InputStream input) throws Exception {
        try (var document = Loader.loadPDF(input.readAllBytes())) {
            var pages = new ArrayList<ParsedPage>();
            var stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = normalize(stripper.getText(document));
                if (!text.isBlank()) pages.add(new ParsedPage(page, text));
            }
            return pages;
        }
    }

    private List<ParsedPage> parseDocx(InputStream input) throws Exception {
        try (var document = new XWPFDocument(input)) {
            var blocks = new ArrayList<String>();
            document.getParagraphs().forEach(p -> {
                if (!p.getText().isBlank()) blocks.add(p.getText());
            });
            for (XWPFTable table : document.getTables()) {
                blocks.add(tableMarkdown(table));
            }
            return List.of(new ParsedPage(1, normalize(String.join("\n\n", blocks))));
        }
    }

    private List<ParsedPage> parseDoc(InputStream input) throws Exception {
        try (var extractor = new WordExtractor(input)) {
            return List.of(new ParsedPage(1, normalize(extractor.getText())));
        }
    }

    private List<ParsedPage> parseExcel(InputStream input) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            var pages = new ArrayList<ParsedPage>();
            var formatter = new DataFormatter();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                int maxColumns = 0;
                for (Row row : sheet) maxColumns = Math.max(maxColumns, row.getLastCellNum());
                if (maxColumns <= 0) continue;
                var markdown = new StringBuilder("## ").append(sheet.getSheetName()).append("\n\n");
                int rowIndex = 0;
                for (Row row : sheet) {
                    markdown.append('|');
                    for (int column = 0; column < maxColumns; column++) {
                        String value = formatter.formatCellValue(row.getCell(column)).replace("|", "\\|").replace("\n", " ");
                        markdown.append(' ').append(value).append(" |");
                    }
                    markdown.append('\n');
                    if (rowIndex++ == 0) {
                        markdown.append('|');
                        for (int column = 0; column < maxColumns; column++) markdown.append(" --- |");
                        markdown.append('\n');
                    }
                }
                String text = normalize(markdown.toString());
                if (!text.isBlank()) pages.add(new ParsedPage(sheetIndex + 1, text));
            }
            return pages;
        }
    }

    private String tableMarkdown(XWPFTable table) {
        var out = new StringBuilder();
        int columns = table.getRows().stream().mapToInt(row -> row.getTableCells().size()).max().orElse(0);
        for (int rowIndex = 0; rowIndex < table.getRows().size(); rowIndex++) {
            var row = table.getRows().get(rowIndex);
            out.append('|');
            for (int column = 0; column < columns; column++) {
                String value = column < row.getTableCells().size() ? row.getCell(column).getText() : "";
                out.append(' ').append(value.replace("|", "\\|").replace("\n", " ")).append(" |");
            }
            out.append('\n');
            if (rowIndex == 0) {
                out.append('|');
                for (int column = 0; column < columns; column++) out.append(" --- |");
                out.append('\n');
            }
        }
        return out.toString();
    }

    private String normalize(String text) {
        return Optional.ofNullable(text).orElse("").replace('\u0000', ' ').replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
