import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';
import Select from 'react-select';

import fetchProcsAction from 'actions/ProcActions';
import safeTraverse from 'utils/safeTraverse';

import styles from './ProcSelectComponent.css';

const noop = () => {};

class ProcSelectComponent extends Component {
  componentDidMount () {
    const { cluster, app } = this.props;
    if (cluster && app) {
      this.props.getProcs({ cluster, app });
    }
  }

  componentWillReceiveProps (nextProps) {
    const didAppChange = nextProps.app !== this.props.app;
    const didClusterChange = nextProps.cluster !== this.props.cluster;
    if (didAppChange || didClusterChange) {
      this.props.getProcs({
        app: nextProps.app,
        cluster: nextProps.cluster,
      });
    }
  }

  render () {
    const { onChange, procs } = this.props;
    const procList = procs.asyncStatus === 'SUCCESS'
      ? procs.data.map(c => ({ name: c })) : [];
    const valueOption = this.props.value && { name: this.props.value };
    return (
      <div>
        <label className={styles.label} htmlFor="proc">Process</label>
        <Select
          id="proc"
          clearable={false}
          options={procList}
          onChange={onChange || noop}
          labelKey="name"
          valueKey="name"
          isLoading={procs.asyncStatus === 'PENDING'}
          value={valueOption}
          noResultsText={procs.asyncStatus !== 'PENDING' ? 'No results found!' : 'Searching...'}
          placeholder="Type to search..."
        />
      </div>
    );
  }
}

const mapStateToProps = (state, ownProps) => ({
  procs: safeTraverse(state, ['procs', ownProps.cluster]) || {},
});

const mapDispatchToProps = dispatch => ({
  getProcs: params => dispatch(fetchProcsAction(params)),
});

ProcSelectComponent.propTypes = {
  app: PropTypes.string,
  cluster: PropTypes.string,
  procs: PropTypes.object.isRequired,
  getProcs: PropTypes.func.isRequired,
  onChange: PropTypes.func,
  value: PropTypes.string,
};

export default connect(mapStateToProps, mapDispatchToProps)(ProcSelectComponent);
