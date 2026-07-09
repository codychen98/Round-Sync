package ca.pkay.rcloneexplorer;

import java.io.File;
import java.util.Comparator;

import ca.pkay.rcloneexplorer.Items.FileItem;

public class FileComparators {

        public static class SortAlphaDescending implements Comparator<FileItem> {

            @Override
            public int compare(FileItem fileItem, FileItem t1) {
                if (fileItem.isDir() && !t1.isDir()) {
                    return -1;
                } else if (!fileItem.isDir() && t1.isDir()) {
                    return 1;
                }

                return -naturalCompare(fileItem.getName(), t1.getName());
            }
        }

        public static class SortAlphaAscending implements Comparator<FileItem> {

            @Override
            public int compare(FileItem fileItem, FileItem t1) {
                if (fileItem.isDir() && !t1.isDir()) {
                    return -1;
                } else if (!fileItem.isDir() && t1.isDir()) {
                    return 1;
                }

                return naturalCompare(fileItem.getName(), t1.getName());
            }
        }

        public static class SortSizeDescending implements Comparator<FileItem> {

            @Override
            public int compare(FileItem fileItem, FileItem t1) {
                if (fileItem.isDir() && !t1.isDir()) {
                    return -1;
                } else if (!fileItem.isDir() && t1.isDir()) {
                    return 1;
                }

                if (fileItem.isDir() && t1.isDir()) {
                    return fileItem.getName().compareTo(t1.getName());
                }

                return Long.compare(t1.getSize(), fileItem.getSize());
            }
        }

        public static class SortSizeAscending implements Comparator<FileItem> {

            @Override
            public int compare(FileItem fileItem, FileItem t1) {
                if (fileItem.isDir() && !t1.isDir()) {
                    return -1;
                } else if (!fileItem.isDir() && t1.isDir()) {
                    return 1;
                }

                if (fileItem.isDir() && t1.isDir()) {
                    return fileItem.getName().compareTo(t1.getName());
                }

                return Long.compare(fileItem.getSize(), t1.getSize());
            }
        }

        public static class SortModTimeDescending implements Comparator<FileItem> {

            @Override
            public int compare(FileItem fileItem, FileItem t1) {
                if (fileItem.isDir() && !t1.isDir()) {
                    return -1;
                } else if (!fileItem.isDir() && t1.isDir()) {
                    return 1;
                }

                return Long.compare(t1.getModTime(), fileItem.getModTime());
            }
        }

        public static class SortModTimeAscending implements Comparator<FileItem> {

            @Override
            public int compare(FileItem fileItem, FileItem t1) {
                if (fileItem.isDir() && !t1.isDir()) {
                    return -1;
                } else if (!fileItem.isDir() && t1.isDir()) {
                    return 1;
                }

                return Long.compare(fileItem.getModTime(), t1.getModTime());
            }
        }


    public static class SortFileAlphaDescending implements Comparator<File> {

        @Override
        public int compare(File file, File t1) {
            if (file.isDirectory() && !t1.isDirectory()) {
                return -1;
            } else if (!file.isDirectory() && t1.isDirectory()) {
                return 1;
            }

            return -naturalCompare(file.getName(), t1.getName());
        }
    }

    public static class SortFileAlphaAscending implements Comparator<File> {

        @Override
        public int compare(File o1, File o2) {
            if (o1.isDirectory() && !o2.isDirectory()) {
                return -1;
            } else if (!o1.isDirectory() && o2.isDirectory()) {
                return 1;
            }

            return naturalCompare(o1.getName(), o2.getName());
        }
    }

    public static class SortFileSizeDescending implements Comparator<File> {

        @Override
        public int compare(File o1, File o2) {
            if (o1.isDirectory() && !o2.isDirectory()) {
                return -1;
            } else if (!o1.isDirectory() && o2.isDirectory()) {
                return 1;
            }

            if (o1.isDirectory() && o2.isDirectory()) {
                return o1.getName().toLowerCase().compareTo(o2.getName().toLowerCase());
            }

            return Long.compare(o2.length(), o1.length());
        }
    }

    public static class SortFileSizeAscending implements Comparator<File> {

        @Override
        public int compare(File o1, File o2) {
            if (o1.isDirectory() && !o2.isDirectory()) {
                return -1;
            } else if (!o1.isDirectory() && o2.isDirectory()) {
                return 1;
            }

            if (o1.isDirectory() && o2.isDirectory()) {
                return o1.getName().toLowerCase().compareTo(o2.getName().toLowerCase());
            }

            return Long.compare(o1.length(), o2.length());
        }
    }

    public static class SortFileModTimeDescending implements Comparator<File> {

        @Override
        public int compare(File o1, File o2) {
            if (o1.isDirectory() && !o2.isDirectory()) {
                return -1;
            } else if (!o1.isDirectory() && o2.isDirectory()) {
                return 1;
            }

            return Long.compare(o2.lastModified(), o1.lastModified());
        }
    }

    public static class SortFileModTimeAscending implements Comparator<File> {

        @Override
        public int compare(File o1, File o2) {
            if (o1.isDirectory() && !o2.isDirectory()) {
                return -1;
            } else if (!o1.isDirectory() && o2.isDirectory()) {
                return 1;
            }

            return Long.compare(o1.lastModified(), o2.lastModified());
        }
    }

    /**
     * Natural (numeric-aware) string comparison.
     * Splits strings into numeric and non-numeric tokens, comparing numbers numerically
     * and text lexicographically. Case-insensitive.
     * Returns: negative if s1 < s2, zero if equal, positive if s1 > s2
     */
    private static int naturalCompare(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return s1 == null ? (s2 == null ? 0 : -1) : 1;
        }
        s1 = s1.toLowerCase();
        s2 = s2.toLowerCase();
        int len1 = s1.length();
        int len2 = s2.length();
        int i1 = 0, i2 = 0;
        while (i1 < len1 && i2 < len2) {
            char c1 = s1.charAt(i1);
            char c2 = s2.charAt(i2);
            if (Character.isDigit(c1) && Character.isDigit(c2)) {
                long num1 = 0, num2 = 0;
                while (i1 < len1 && Character.isDigit(s1.charAt(i1))) {
                    num1 = num1 * 10 + (s1.charAt(i1) - '0');
                    i1++;
                }
                while (i2 < len2 && Character.isDigit(s2.charAt(i2))) {
                    num2 = num2 * 10 + (s2.charAt(i2) - '0');
                    i2++;
                }
                if (num1 != num2) return Long.compare(num1, num2);
            } else {
                if (c1 != c2) return Character.compare(c1, c2);
                i1++;
                i2++;
            }
        }
        return Integer.compare(len1, len2);
    }
}
