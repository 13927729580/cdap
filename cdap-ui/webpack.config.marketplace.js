/*
 * Copyright © 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
var webpack = require('webpack');
var plugins = [
  new webpack.optimize.DedupePlugin(),
  new webpack.optimize.CommonsChunkPlugin("marketplace-lib", "marketplace-lib.js", Infinity),
  new webpack.optimize.UglifyJsPlugin({
    compress: {
      warnings: false,
      comments: false
    }
  })
];
var mode = process.env.NODE_ENV;
if (mode === 'production' || mode === 'build') {
  plugins.push(
    new webpack.DefinePlugin({
      'process.env':{
        'NODE_ENV': JSON.stringify("production"),
        '__DEVTOOLS__': false
      },
    })
  );
}
var loaders = [
  {
    test: /\.less$/,
    loader: 'style-loader!css-loader!less-loader'
  },
  {
    test: /\.ya?ml$/,
    loader: 'yml'
  },
  {
    test: /\.css$/,
    loader: 'style-loader!css-loader!less-loader'
  },
  {
    test: /\.js$/,
    loader: 'babel',
    exclude: /node_modules/,
    query: {
      plugins: ['lodash'],
      presets: ['react', 'es2015']
    }
  }
];

module.exports = {
  context: __dirname + '/app/market',
  entry: {
    'marketplace': ['./market.js'],
    'marketplace-lib': [
      'classnames',
      'reactstrap',
      'i18n-react',
      'sockjs-client',
      'rx',
      'react-addons-css-transition-group',
      'react-dropzone',
      'react-redux'
    ]
  },
  module: {
    preLoaders: [
      {
        test: /\.js$/,
        loader: 'eslint-loader',
        exclude: [
          /node_modules/,
          /bower_components/,
          /dist/,
          /cdap_dist/
        ]
      }
    ],
    loaders: loaders
  },
  output: {
    filename: './[name].js',
    path: __dirname + '/market_dist',
    library: 'Market',
    libraryTarget: 'umd'
  },
  externals: {
    'react': {
      root: 'React',
      commonjs2: 'react',
      commonjs: 'react',
      amd: 'react'
    },
    'react-dom': {
      root: 'ReactDOM',
      commonjs2: 'react-dom',
      commonjs: 'react-dom',
      amd: 'react-dom'
    }
  },
  resolve: {
    alias: {
      components: __dirname + '/app/cdap/components',
      services: __dirname + '/app/cdap/services',
      api: __dirname + '/app/cdap/api'
    }
  }
};
